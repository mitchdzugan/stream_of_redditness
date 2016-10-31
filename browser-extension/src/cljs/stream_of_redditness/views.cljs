(ns stream-of-redditness.views
    (:require [re-frame.core :as re-frame]
              [reagent.core :as reagent]
              [posh.reagent :as p]
              [stream-of-redditness-core.db :as db]
              [datascript.core :as d]
              [re-com.core :refer [h-box v-box hyperlink-href box]]))

(defn auth-view
  []
  (fn []
    [h-box
     :children [[hyperlink-href
                 :label "Add a reddit account"
                 :href (str "https://www.reddit.com/api/v1/authorize"
                            "?client_id=" "-ko_iFMT1UDKOQ"
                            "&response_type=" "code"
                            "&state=" "state"
                            "&redirect_uri=" "https://www.reddit.com/r/StreamReddit/auth"
                            "&duration=" "permanent"
                            "&scope=" "edit read report save submit vote identity")
                 :target "_blank"
                 :attr {:on-click #(re-frame/dispatch [:auth-flow-begin])}]]]))

(defn comment-view
  [id]
  (let [{:keys [comment/body comment/score comment/created
                comment/author comment/children]}
        @(p/pull db/conn [:comment/body :comment/score
                          :comment/created :comment/author
                          {:comment/children [:db/id :comment/id
                                              :comment/created :comment/loaded]}]
                 id)
        replies (->> children
                     (filter :comment/loaded)
                     (sort-by :comment/created)
                     reverse)]
    [:li.list-group-item
     [v-box
      :children [[box
                  :child [h-box
                          :children [[box
                                      :size "none"
                                      :align-self :center
                                      :child [:span.badge (str score)]]
                                     [box
                                      :size "1"
                                      :child [v-box
                                              :width "100%"
                                              :children [[box :child [h-box
                                                                      :justify :between
                                                                      :children [[box :child author]
                                                                                 [box :child (str created)]]]]
                                                         [box :child body]]]]]]]
                 (if (> (count replies) 0)
                   [box
                    :child [:ul.list-group
                            (for [comment replies]
                              ^{:key (:comment/id comment)}
                              [comment-view (:db/id comment)])]])]]]))

(defn comment-stream
  []
  (let [{{:keys [render/comments render/last-char-count]} :root/render :as all}
        @(p/pull db/conn '[{:root/render [:render/last-char-count :render/comments]}] 0)]
    [:ul.list-group
     (for [comment comments]
       ^{:key (:db/id comment)} [comment-view (:db/id comment)])]))

(defn main-panel []
  (fn []
    [v-box
     :children [[box :child [auth-view]]
                [box :child [comment-stream]]]]))
