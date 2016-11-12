(ns stream-of-redditness-core.util
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [stream-of-redditness-core.db :as db]
            [cljs.core.async :refer [put! chan <! >! timeout close!]]
            [datascript.core :as d]))

(defn merge-with-key
  [f & maps]
  (when (some identity maps)
    (let [merge-entry (fn [m e]
                        (let [k (key e) v (val e)]
                          (if (contains? m k)
                            (assoc m k (f k (get m k) v))
                            (assoc m k v))))
          merge2 (fn [m1 m2]
                   (reduce merge-entry (or m1 {}) (seq m2)))]
      (reduce merge2 maps))))

(defn pop-when
  [l pred]
  (reduce (fn [[popped filtered] v]
            (if (and (nil? popped) (pred v))
              [v filtered]
              [popped (conj filtered v)])) [nil []] l))

(comment

  (pop-when [1 2 3] #(-> true))

  )

(defn deep-merge-rec
  [root last-key source changes]
  (cond
    (map? source) (merge-with-key (partial deep-merge-rec root) source changes)
    (sequential? source) (let [[updated changes-left]
                               (reduce
                                (fn [[changed changes] v]
                                  (let [pred (if-let [p (get root (-> last-key str (subs 1) (#(str "_" %)) keyword))]
                                               p
                                               #(-> false))
                                        [change changes] (pop-when changes (partial pred v))]
                                    [(conj changed
                                           (if change
                                             (deep-merge-rec root last v change)
                                             v))
                                     changes])) [[] changes] source)]
                           (reduce conj updated changes-left))
    :else changes))

(defn deep-merge
  [source changes]
  (deep-merge-rec (merge source changes) nil source changes))

(defn comment-to-datoms
  [c l {:keys [kind data]}]
  (if (= "more" kind)
    (-> (reduce conj l (map #(assoc data :kind :more :children %) (partition-all 20 (:children data))))
        (conj {:db/id (db/tempid)
               :comment/id (:id data)}))
    (let [{:keys [id score author body created_utc replies parent_id gilded edited
                  author_flair_css_class score_hidden author_flair_text]} data]
      (go (>! c [id body (.md5 js/window body)]))
      (conj
       (reduce (partial comment-to-datoms c)
               l
               (get-in replies [:data :children]))
       {:db/id (db/tempid)
        :comment/id id
        :comment/score score
        :comment/author author
        :comment/body body
        :comment/created created_utc
        :comment/parent parent_id
        :comment/gilded? (or gilded false)
        :comment/edited? (or edited false)
        :comment/author-flair-css-class (or author_flair_css_class "")
        :comment/score-hidden? (or score_hidden false)
        :comment/author-flair-text (or author_flair_text "")
        :comment/children (->> replies
                               :data
                               :children
                               (map #(get-in % [:data :id]))
                               (map #(-> [:comment/id %])))}))))

(defn go-apply
  [f & args]
  (let [c (chan)]
    (apply f (conj args c))
    c))

(defn go-list-op
  [reducer i l wait]
  (let [c (chan)]
    (go (let [c-first (chan)
              _ (go (>! c-first i))
              c-last (reduce (fn [c-read v]
                               (let [c-write (chan)]
                                 (go (let [acc (<! c-read)]
                                       (<! (timeout wait))
                                       (reducer c-write acc v)))
                                 c-write))
                             c-first
                             l)
              res (<! c-last)]
          (>! c res)))
    c))

(defn go-reduce
  ([f i l] (go-reduce f i l 0))
  ([f i l wait]
   (go-list-op #(go (>! %1 (<! (go-apply f %2 %3))))
               i
               l
               wait)))

(defn go-some
  ([p l] (go-some p l 0))
  ([p l wait]
   (go-list-op #(go (>! %1 (if %2 %2 (or (<! (go-apply p %3))))))
               false
               l
               wait)))

(defn go-filter
  ([p l] (go-filter p l 0))
  ([p l wait]
   (go-list-op #(go (let [use? (<! (go-apply p %3))]
                      (>! %1 (if use? (conj %2 %3) %2))))
               []
               l
               wait)))
