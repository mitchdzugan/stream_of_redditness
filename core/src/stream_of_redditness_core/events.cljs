(ns stream-of-redditness-core.events
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [re-frame.core :as re-frame]
            [stream-of-redditness-core.db :as db]
            [stream-of-redditness-core.util :as util]
            [day8.re-frame.http-fx]
            [posh.reagent :as posh]
            [datascript.core :as d]
            [ajax.core :as ajax]
            [clojure.data :as data]
            [re-frame.loggers :refer [console]]
            [cljs.core.async :refer [put! chan <! >! timeout close!]]
            [cljs.core.match :refer-macros [match]]
            [dat-harmony.core :as dat-harmony]))

(re-frame/reg-cofx
 :datascript
 (fn [coeffects [selector eidf]]
   (assoc coeffects :datascript (d/pull @db/conn selector (eidf (:event coeffects))))))

(re-frame/reg-cofx
 :datascript-db
 (fn [coeffects [selector _]]
   (assoc coeffects :datascript-db @db/conn)))

(re-frame/reg-cofx
 :now
 (fn [coeffects _]
   (assoc coeffects :now (.now js/Date))))

(re-frame/reg-cofx
 :heights
 (fn [coeffects _]
   (let [app (.getElementById js/document "app")])
   (assoc coeffects :heights {:display-height (.-innerHeight js/window)
                              :rendered-height (-> js/document
                                                        (.getElementById "app")
                                                        .-clientHeight)
                              :scroll-top (-> js/document
                                              (.getElementsByTagName "body")
                                              (.item 0)
                                              .-scrollTop)})))
(defn handle-transactions
  [{:keys [transactions dont-save async]}]
  (letfn [(create-datoms-at
            [{:keys [datoms path]}]
            (if path
              (let [eid-root (first path)
                    sel (->> path
                             rest
                             reverse
                             (reduce #(assoc {} %2 [%1]) :db/id)
                             (#(-> [%])))
                    pull-res (d/pull @db/conn sel eid-root)
                    eid (or (get-in pull-res (concat (rest path) [:db/id]))
                            (db/tempid))]
                (#(-> [%]) (flatten
                            (concat
                             (if-not pull-res
                               (let [path-init (-> path reverse rest reverse)
                                     path-last (-> path reverse first)]
                                 (if (> (count path-init) 1)
                                   (create-datoms-at
                                    {:datoms [{path-last eid}]
                                     :path path-init})
                                   [{:db/id (first path-init)
                                     path-last eid}])))
                             (map #(assoc % :db/id eid) datoms)))))
              datoms))]
    (let [n (.now js/Date)
          all-datoms (mapcat create-datoms-at transactions)
          first-chan (chan)
          last-chan (if async
                      (reduce (fn [read-chan datom]
                                        (let [write-chan (chan)]
                                          (go (<! read-chan)
                                              (d/transact! db/conn (flatten [datom]))
                                              (>! write-chan true)
                                              )
                                          write-chan)) first-chan all-datoms)
                      (let [c (chan)]
                        (d/transact! db/conn (flatten all-datoms))
                        (go (<! first-chan)
                            (>! c true))
                        c))]
      (go (>! first-chan true)
          (<! last-chan)
          (re-frame/dispatch [:datascript-transact-complete])
          (if-not dont-save (.setItem js/localStorage
                                       "sor-datoms"
                                       (dat-harmony/pull-datoms
                                        d/pull-many @db/conn
                                        [{:root/auth [:auth/flow
                                                      :auth/error
                                                      {:auth/current-user [:user/name
                                                                           :user/token
                                                                           :user/refresh]}
                                                      {:auth/users [:user/name
                                                                    :user/token
                                                                    :user/refresh]}]}]
                                        0)))))))

(defn datascript-interceptor
  "An interceptor which logs data about the handling of an event.
  Includes a `clojure.data/diff` of the db, before vs after, showing
  the changes caused by the event handler.
  You'd typically want this interceptor after (to the right of) any
  path interceptor."
  [debug]
  (re-frame/->interceptor
   :id     :datascript-debug
   :before (fn debug-before
             [context]
             (if debug
               (console :log "Handling re-frame event:" (re-frame/get-coeffect context :event)))
             context)
   :after  (fn debug-after
             [context]
             (let [fdb (fn [db] (update-in db
                                           [:root/polling :polling/threads]
                                           (partial map #(dissoc % :thread/top-level-comments))))
                   event   (re-frame/get-coeffect context :event)
                   orig-db (if debug (fdb (d/pull @db/conn '[*] 0)))
                   transactions (re-frame/get-effect context :datascript-transact ::not-found)]
               (if (= transactions ::not-found)
                 (if debug (console :log "No :db changes caused by:" event))
                 (do
                   (handle-transactions transactions)
                   (if debug
                     (let [new-db (fdb (d/pull @db/conn '[*] 0))
                           [only-before only-after] (data/diff orig-db new-db)
                           db-changed?    (or (some? only-before) (some? only-after))]
                       (if db-changed?
                         (do (console :group "db clojure.data/diff for:" event)
                             (console :log "only before:" only-before)
                             (console :log "only after :" only-after)
                             (console :groupEnd))
                         (console :log "no app-db changes caused by:" event))))))
               (update-in context [:effects] dissoc :datascript-transact)))))

(def interceptors #(-> [(re-frame/inject-cofx :datascript [%1 %2])
                        (re-frame/inject-cofx :datascript-db)
                        (re-frame/inject-cofx :now)
                        (re-frame/inject-cofx :heights)
                        (re-frame/inject-cofx :ws)
                        (datascript-interceptor false)]))

(defn reg-event-fx
  ([id f] (reg-event-fx id [] #(-> 0) f))
  ([id sel f] (reg-event-fx id sel #(-> 0) f))
  ([id sel eidf f] (re-frame/reg-event-fx id (interceptors sel eidf) f)))

(re-frame/reg-fx
 :schedule-poll
 (fn [_]
   (.setTimeout js/window #(re-frame/dispatch [:poll-reddit :poll]) 10000)))

(re-frame/reg-fx
 :listen-storage
 (fn [_]
   (.addEventListener js/window
                      "storage"
                      #(if (=
                            "sor-datoms"
                            (.-key %))
                         (re-frame/dispatch
                          [:storage-update (-> %
                                               .-newValue
                                               cljs.reader/read-string)])))))

(defn take-while-+1
  [p l]
  (match [l]
         [([] :seq)] '()
         [([(x :guard p) & xs] :seq)] (conj (take-while-+1 p xs) x)
         [([x & xs] :seq)] (list x)))

(defn comment-char-count
  [{:keys [comment/loaded comment/body comment/children]}]
  (if loaded
    (reduce #(+ %1 (comment-char-count %2)) (count body) children)
    0))

(defn comment-char-count-tl
  [db id]
  (let [
        {:keys [comment/loaded comment/body comment/children]}
        (d/pull db '[*] id)
        ]
    (if loaded
      (reduce #(+ %1 (comment-char-count %2)) (count body) children)
      0)))

(re-frame/reg-fx
 :calculate-for-render
 (fn [{:keys [display-height rendered-height scroll-top db]}]
   (let [{{:keys [render/last-char-count render/last-id]} :root/render
          {:keys [polling/threads]} :root/polling}
         (d/pull db [{:root/render [:render/last-char-count :render/last-id]}
                     {:root/polling [{:polling/threads [{:thread/top-level-comments [:db/id :comment/created]}]}]}] 0)
         all-comments (->> threads
                           (mapcat :thread/top-level-comments)
                           (sort-by #(* -1 (:comment/created %))))]
     (re-frame/dispatch
      [:commit-for-render
       (if (> last-char-count 0)
         (let [target-char-count (-> display-height
                                     (* 5)
                                     (+ scroll-top)
                                     (* (/ last-char-count rendered-height)))
               {:keys [char-count id]} (reduce (fn [{:keys [char-count seen-last] :as acc}
                                                         {:keys [db/id]}]
                                                 {:seen-last (or seen-last (not last-id) (= last-id id))
                                                  :char-count (if (< char-count target-char-count)
                                                                (+ char-count (comment-char-count-tl db id))
                                                                char-count)
                                                  :id (if (and seen-last
                                                               (>= char-count target-char-count))
                                                        (:id acc)
                                                        id)})
                                               {:char-count 0}
                                               all-comments)]
           {:char-count char-count
            :comments (->> all-comments
                           (take-while-+1 #(not= (:db/id %) id)))
            :last-id id})
         (let [comments (take 20 all-comments)]
           {:comments comments
            :char-count (reduce #(+ %1 (comment-char-count-tl db (:db/id %2))) 0 comments)}))]))))

(re-frame/reg-cofx
  :ws
  (fn [coeffects _]
    (assoc coeffects :ws nil)))

(re-frame/reg-fx
 :websocket
 (fn [{:keys [host onmessage]}]
   (let [ws (js/WebSocket. host)]
     (re-frame/reg-cofx
      :ws
      (fn [coeffects _]
        (assoc coeffects :ws ws)))
     (set! (.-onmessage ws) #(re-frame/dispatch [onmessage %])))))

(defmulti initial-dispatch :root/view)
(defmethod initial-dispatch :auth [_] [:auth-flow-submit-code])
(defmethod initial-dispatch :stream [_] [:poll-reddit :init])

(reg-event-fx
 :datascript-transact-complete
 (fn [_ _] {}))

(reg-event-fx
 :storage-update
 (fn [_ [_ datoms]]
   {:datascript-transact {:transactions [{:datoms datoms}]
                          :dont-save true}}))

(reg-event-fx
 :initialize-db
 (fn  [_ _]
   {:db {}
    :datascript-transact {:transactions (db/init-db)}
    :dispatch [:initial-route-dispatch]
    :listen-storage :no-args-needed
    :websocket {:host "ws://localhost:8000"
                :onmessage :websocket-message}}))

(reg-event-fx
 :websocket-message
 (fn [_ _]
   {}))

(reg-event-fx
 :initial-route-dispatch
 [:root/view]
 (fn [{:keys [datascript]}]
   {:dispatch (initial-dispatch datascript)}))

(reg-event-fx
 :poll-reddit
 [{:root/polling [:polling/is-polling?
                  :polling/polls-since-root
                  {:polling/threads [:thread/id :thread/mores :thread/last-poll]}]}
  {:root/auth [{:auth/current-user [:user/token]}]}]
 (fn [{{{:keys [polling/is-polling? polling/polls-since-root polling/threads]} :root/polling
        {{:keys [user/token]} :auth/current-user} :root/auth} :datascript
       now :now} _ poll-type]
   (if-not (and (= :init poll-type) is-polling?)
     (if-let [thread-to-poll (->> threads
                                  (sort-by :thread/last-poll)
                                  first)]
       (let [polls-since-root (or polls-since-root 0)
             poll-root? (or (not token)
                            (> polls-since-root 3)
                            (empty? (:thread/mores thread-to-poll)))
             [{:keys [children]} mores] (util/pop-when (:thread/mores thread-to-poll) #(not poll-root?))
             api-call (if poll-root?
                        {:method          :get
                         :uri             (str "https://www.reddit.com/comments/" (:thread/id thread-to-poll) ".json?sort=new")
                         :response-format (ajax/json-response-format {:keywords? true})
                         :on-success      [:root-reddit-poll-res (:thread/id thread-to-poll)]}
                        {:method          :get
                         :uri             "https://oauth.reddit.com/api/morechildren/"
                         :response-format (ajax/json-response-format {:keywords? true})
                         :format          :json
                         :params          {:api_type "json"
                                           :children (->> children
                                                          (reduce #(str %1 "," %2) "")
                                                          (#(subs % 1)))
                                           :link_id (str "t3_" (:thread/id thread-to-poll))
                                           :sort "new"}
                         :on-success      [:more-reddit-poll-res (:thread/id thread-to-poll)]
                         :headers         {:authorization (str "bearer " token)
                                           :content-type "application/json; charset=UTF-8"}})]
         {:dispatch [:poll-request api-call]
          :datascript-transact {:transactions [{:path [0 :root/polling]
                                                :datoms [{:polling/is-polling? true
                                                          :polling/polls-since-root (if poll-root?
                                                                                      0
                                                                                      (inc polls-since-root))}]}
                                               {:datoms [{:db/id [:thread/id (:thread/id thread-to-poll)]
                                                          :thread/last-poll now
                                                          :thread/mores mores}]}]}})
       {:datascript-transact {:transactions [{:datoms [{:db/id 0
                                                        :is-polling false}]}]}}))))

(reg-event-fx
 :poll-request
 [{:root/auth [{:auth/current-user [:user/token]}]}
  {:root/polling [:polling/calls-since-poll]}]
 (fn [{{{:keys [polling/calls-since-poll] :or {polling/calls-since-poll 0}} :root/polling
        {{:keys [user/token]} :auth/current-user} :root/auth} :datascript} [_ api-call]]
   (if (= 0 calls-since-poll)
     {:dispatch [:reddit-api-request api-call]}
     {:dispatch-later [{:ms (* (if token 1000 30000) calls-since-poll) :dispatch [:poll-request api-call]}]
      :datascript-transact {:transactions [{:path [0 :root/polling]
                                            :datoms [{:polling/calls-since-poll 0}]}]}})))

(reg-event-fx
 :on-scroll
 [{:root/render [:render/scroll-requested-in-progress?]}]
 (fn [{{{:keys [render/scroll-requested-in-progress?]} :root/render} :datascript
       {:keys [display-height scroll-top rendered-height]} :heights}]
   (let [scroll-percentage (-> scroll-top
                               (+ display-height)
                               (+ scroll-top)
                               (/ 2)
                               (/ rendered-height))]
     (cond
       (and (> scroll-percentage 0.66) (not scroll-requested-in-progress?))
       {:dispatch [:prepare-select-for-render]
        :datascript-transact {:transactions [{:path [0 :root/render]
                                              :datoms [{:render/scroll-requested-in-progress? true}]}]}}
       (and (< scroll-percentage 0.33) (not scroll-requested-in-progress?))
       {:dispatch [:prepare-select-for-render]
        :datascript-transact {:transactions [{:path [0 :root/render]
                                              :datoms [{:render/scroll-requested-in-progress? true
                                                        :render/last-id false}]}]}}
       :else {}))))

(reg-event-fx
 :reddit-api-request
 [{:root/polling [:polling/calls-since-poll]}]
 (fn [{{{:keys [polling/calls-since-poll] :or {polling/calls-since-poll 0}}
        :root/polling} :datascript} [_ api-call]]
   (let [on-failure (:on-failure api-call)]
     {:http-xhrio (merge api-call {:on-failure [:reddit-api-request-failed api-call on-failure]})
      :datascript-transact {:transactions [{:path [0 :root/polling]
                                            :datoms [{:polling/calls-since-poll (inc calls-since-poll)}]}]}})))

(reg-event-fx
 :reddit-api-request-failed
 [{:root/auth [{:auth/current-user [:user/refresh]}]}]
 (fn [{{{{:keys [user/refresh]} :auth/current-user} :root/auth} :datascript}
      [_ api-call on-failure {:keys [response]}]]
   (if (and (= "Unauthorized" (:message response)) (= 401 (:error response)))
     {:dispatch [:reddit-api-request
                 {:method          :post
                  :uri             "https://www.reddit.com/api/v1/access_token"
                  :response-format (ajax/json-response-format {:keywords? true})
                  :body            (str "grant_type=refresh_token"
                                        "&refresh_token=" refresh)
                  :on-success      [:refresh-success api-call on-failure]
                  :on-failure      [:refresh-failed on-failure]
                  :headers         {:authorization "Basic LWtvX2lGTVQxVURLT1E6"
                                    :content-type "application/x-www-form-urlencoded"}}]}
     (if on-failure {:dispatch on-failure} {:dispatch [:poll-reddit :loop]}))))

(reg-event-fx
 :refresh-success
 [{:root/auth [{:auth/current-user [:user/name]}]}]
 (fn [{{{{:keys [user/name]} :auth/current-user} :root/auth} :datascript}
      [_ api-call on-failure {:keys [error access_token]}]]
   (if error
     {:dispatch on-failure}
     {:datascript-transact {:transactions [{:datoms [{:db/id [:user/name name]
                                                      :user/token access_token}]}]}
      :dispatch [:reddit-api-request (assoc-in api-call
                                               [:headers :authorization]
                                               (str "bearer " access_token))]})))

(reg-event-fx
 :refresh-failed
 (fn [_ _]
   (println "Failed Refresh :(")))

(reg-event-fx
 :commit-for-render
 (fn [_ [_ {:keys [comments char-count last-id]}]]
   {:datascript-transact {:transactions [{:path [0 :root/render]
                                          :datoms [{:render/scroll-requested-in-progress? false
                                                    :render/last-char-count char-count
                                                    :render/comments comments
                                                    :render/last-id (or last-id false)}]}]}}))

(reg-event-fx
 :prepare-select-for-render
 (fn [{{:keys [scroll-top rendered-height display-height]} :heights
       db :datascript-db}]
   {:calculate-for-render {:scroll-top scroll-top
                           :rendered-height rendered-height
                           :display-height display-height
                           :db db}}))

(defn process-comments
  [mores thread-id res root-path]
  (let [all-data (reduce util/comment-to-datoms [] (get-in res root-path))
        pred #(= :more (:kind %))
        [new-mores datoms] [(filter pred all-data) (remove pred all-data)]]
    {:dispatch-n [[:poll-reddit :loop]
                  [:prepare-select-for-render]]
     :datascript-transact
     {:transactions [{:datoms datoms}
                     {:datoms [{:db/id [:thread/id thread-id]
                                :thread/mores (concat mores new-mores)}]}
                     {:datoms [(->> datoms
                                    (#(do (-> %
                                              (filter (fn [x] (nil? (:comment/parent x))))
                                              (map println))
                                          %))
                                    (remove #(nil? (:comment/parent %)))
                                    (map #(if (= (str "t3_" thread-id)
                                                 (:comment/parent %))
                                            {:db/id [:thread/id thread-id]
                                             :thread/top-level-comments [:comment/id (:comment/id %)]}
                                            {:db/id [:comment/id (subs (:comment/parent %) 3)]
                                             :comment/children [:comment/id (:comment/id %)]})))]}]
      :async false}}))

(reg-event-fx
 :root-reddit-poll-res
 [:thread/mores :thread/last-poll]
 (fn [[_ thread-id _]] [:thread/id thread-id])
 (fn [{{:keys [thread/mores]} :datascript} [_ thread-id res]]
   (process-comments mores thread-id res [1 :data :children])))

(reg-event-fx
 :more-reddit-poll-res
 [:thread/mores :thread/last-poll]
 (fn [[_ thread-id _]] [:thread/id thread-id])
 (fn [{{:keys [thread/mores]} :datascript} [_ thread-id res]]
   (process-comments mores thread-id res [:json :data :things])))

(reg-event-fx
 :reddit-auth-error
 (fn [_ v]
   {:datascript-transact {:transactions [{:path [0 :root/auth]
                                          :datoms [{:auth/flow :error
                                                    :auth/error v}]}]}}))

(reg-event-fx
 :auth-flow-begin
 (fn [_ _]
   {:datascript-transact {:transactions [{:path [0 :root/auth]
                                          :datoms [{:auth/flow :begin}]}]}}))

(reg-event-fx
 :auth-flow-submit-code
 [:code]
 (fn [{:keys [datascript]} _]
   {:datascript-transact {:transactions [{:path [0 :root/auth]
                                          :datoms [{:auth/flow :submit-code}]}]}
    :http-xhrio {:method          :post
                 :uri             "https://www.reddit.com/api/v1/access_token"
                 :response-format (ajax/json-response-format {:keywords? true})
                 :body            (str
                                   "grant_type=authorization_code"
                                   "&code=" (:code datascript)
                                   "&redirect_uri=https://www.reddit.com/r/StreamReddit/auth")
                 :on-success      [:auth-flow-token-success]
                 :on-failure      [:reddit-auth-error]
                 :headers         {:authorization "Basic LWtvX2lGTVQxVURLT1E6"
                                   :content-type "application/x-www-form-urlencoded"}}}))

(reg-event-fx
 :auth-flow-token-success
 (fn [_ [_ {:keys [error access_token refresh_token]}]]
   (if error
     {:dispatch [:reddit-auth-error error]}
     {:datascript-transact {:transactions [{:path [0 :root/auth]
                                            :datoms [{:auth/flow :get-me}]}]}
      :http-xhrio {:method          :get
                   :uri             "https://oauth.reddit.com/api/v1/me"
                   :response-format (ajax/json-response-format {:keywords? true})
                   :on-success      [:auth-flow-me-success access_token refresh_token]
                   :on-failure      [:reddit-auth-error]
                   :headers         {:authorization (str "bearer " access_token)}}})))

(reg-event-fx
 :auth-flow-me-success
 (fn [_ [_ token refresh {:keys [name]}]]
   {:datascript-transact {:transactions [{:datoms [{:db/id (db/tempid)
                                                    :user/name name
                                                    :user/token token
                                                    :user/refresh refresh}]}
                                         {:path [0 :root/auth]
                                          :datoms [{:auth/flow :complete
                                                    :auth/current-user [:user/name name]
                                                    :auth/users [:user/name name]}]}]}}))
