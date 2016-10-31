(ns stream-of-redditness-core.util
  (:require [stream-of-redditness-core.db :as db]
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
  [l {:keys [kind data]}]
  (if (= "more" kind)
    (-> (reduce conj l (map #(assoc data :kind :more :children %) (partition-all 20 (:children data))))
        ;;(conj (assoc data :kind :more))
        (conj {:db/id (db/tempid)
               :comment/id (:id data)
               :comment/loaded false}))
    (let [{:keys [id score author body created_utc replies parent_id]} data]
      (conj
       (reduce comment-to-datoms l (get-in replies [:data :children]))
       {:db/id (db/tempid)
        :comment/id id
        :comment/loaded true
        :comment/score score
        :comment/author author
        :comment/body body
        :comment/created created_utc
        :comment/parent parent_id
        :comment/children (->> replies
                               :data
                               :children
                               (map #(get-in % [:data :id]))
                               (map #(-> [:comment/id %])))}))))
