(ns stream-of-redditness-core.db
  (:require [datascript.core :as d]
            [posh.reagent :as posh]
            [stream-of-redditness-core.routes :as routes]
            [cemerick.url :as url]
            [clojure.walk :as walk]
            [clojure.spec :as s]
            [clojure.string :as string]))

(defn make-schema
  [schema]
  (->> (concat (map #(-> [% {:db/unique :db.unique/identity
                             :db/index true}]) (:ident schema))
               (map #(-> [% {:db/valueType :db.type/ref
                             :db/isComponent true}]) (:single-ref schema))
               (map #(-> [% {:db/valueType :db.type/ref
                             :db/cardinality :db.cardinality/many
                             :db/isComponent true}]) (:many-ref schema)))
       (into {})))

(def schema (make-schema {:ident      [:user/name
                                       :comment/id
                                       :thread/id
                                       :markdown/hash]
                          :single-ref [:root/auth
                                       :auth/current-user
                                       :root/polling
                                       :root/routing
                                       :root/render
                                       :comment/body]
                          :many-ref   [:auth/users
                                       :polling/threads
                                       :thread/top-level-comments
                                       :comment/children]}))

(def conn (d/create-conn schema))

(posh/posh! conn)

(def tempid-ref (atom -1))
(defn tempid
  []
  (let [id @tempid-ref]
    (swap! tempid-ref dec)
    id))

(defmulti initial-state-for-route (fn [handler _ _] handler))
(defmethod initial-state-for-route :auth [_ _ query-params] query-params
  {:datoms [(merge {:db/id 0} (walk/keywordize-keys query-params))]})
(defmethod initial-state-for-route :stream [_ route-params _]
  {:path [0 :root/polling :polling/threads]
   :datoms (as-> (:threads route-params) $
             (string/split $ "-")
             (map #(-> {:thread/id %}) $))})
(defmethod initial-state-for-route :default [_ _ _] {:root/threads []})

(defn init-db
  []
  (let [location (url/url (-> js/window .-location .toString))
        {:keys [handler route-params]} (routes/match-route (:path location))
        view (or handler :stream)]
    (flatten
     [{:datoms [{:db/id 0
                 :db/position :root}]}
      {:datoms (try (let [datoms (->> "sor-datoms"
                                      (.getItem js/localStorage)
                                      cljs.reader/read-string)]
                      (if (s/valid? (s/coll-of (fn [{:keys [db/id]}] (int? id))) datoms)
                        datoms
                        []))
                    (catch js/Object e
                      []))}
      {:datoms [{:db/id 0
                 :root/view view}]}
      (initial-state-for-route view route-params (:query location))])))
