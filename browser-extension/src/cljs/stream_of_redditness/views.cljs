(ns stream-of-redditness.views
    (:require [re-frame.core :as re-frame]
              [reagent.core :as reagent]
              [posh.reagent :as p]
              [stream-of-redditness-core.db :as db]
              [datascript.core :as d]
              [re-com.core :refer [h-box v-box hyperlink-href box single-dropdown]]))

(defn top-panel
  []
  (fn []
    (let [{{{:keys [db/id user/name]} :auth/current-user users :auth/users} :root/auth}
          @(p/pull db/conn [{:root/auth [{:auth/users [:db/id
                                                       :user/name]}
                                         {:auth/current-user [:db/id
                                                              :user/name]}]}] 0)
          dropdown-choices (reverse (conj (->> users
                                               (map #(-> {:id (:db/id %)
                                                          :label (:user/name %)}))
                                               (sort-by #(= id (:id %))))
                                          {:id :add-account
                                           :label name}))
          render-fn #(if (= :add-account (:id %)) "Add a Reddit account" (:label %))
          on-selection #(if (= :add-account %)
                          (do
                            (.open js/window
                                   (str "https://www.reddit.com/api/v1/authorize"
                                        "?client_id=" "-ko_iFMT1UDKOQ"
                                        "&response_type=" "code"
                                        "&state=" "state"
                                        "&redirect_uri=" "https://www.reddit.com/r/StreamReddit/auth"
                                        "&duration=" "permanent"
                                        "&scope=" "edit read report save submit vote identity")
                                   "_blank")
                            (re-frame/dispatch [:auth-flow-begin]))
                          (re-frame/dispatch [:switch-account %]))]
      [h-box
       :children [[box
                   :size "1"
                   :child
                   (if (> (count users) 0)
                     [h-box
                      :children [[box :child "Logged in as: "]
                                 [box :size "1"
                                  :child [single-dropdown
                                          :choices dropdown-choices
                                          :model id
                                          :render-fn render-fn
                                          :on-change on-selection
                                          :placeholder "You are not logged in"]]]]
                     [h-box
                      :children ["You are not logged in:"
                                 [hyperlink-href
                                  :label "Add a reddit account"
                                  :href (str "https://www.reddit.com/api/v1/authorize"
                                             "?client_id=" "-ko_iFMT1UDKOQ"
                                             "&response_type=" "code"
                                             "&state=" "state"
                                             "&redirect_uri=" "https://www.reddit.com/r/StreamReddit/auth"
                                             "&duration=" "permanent"
                                             "&scope=" "edit read report save submit vote identity")
                                  :target "_blank"
                                  :attr {:on-click #(re-frame/dispatch [:auth-flow-begin])}]]])]
                  [box
                   :size "1"
                   :child "add thread"]]])))

(defn comment-view
  [id color]
  (let [{:keys [comment/markdown comment/score comment/created
                comment/author comment/children]}
        @(p/pull db/conn [{:comment/markdown [:markdown/parsed]} :comment/score
                          :comment/created :comment/author :comment/id
                          {:comment/children [:db/id :comment/created :comment/loaded]}]
                 id)
        replies (->> children
                     (filter :comment/loaded)
                     (sort-by :comment/created)
                     reverse)]
    [:li.list-group-item {:id id}
     [h-box
      :children [[box
                  :size (if (nil? color) "0px" "100px")
                  :child [:div {:style {:background-color (str "#" color)
                                        :height "100%"
                                        :width "100%"}} ""]]
                 [box
                  :size "1"
                  :child [v-box
                          :width "100%"
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
                                                                                                     [box :child (.fromNow (.moment js/window (* 1000 created)))]]]]
                                                                             [box :child (str (:markdown/parsed markdown))]]]]]]]
                                     (if (> (count replies) 0)
                                       [box
                                        :child [:ul.list-group
                                                (for [comment replies]
                                                  ^{:key (:db/id comment)}
                                                  [comment-view (:db/id comment) nil])]])]]]]]]))

(defn comment-bookend
  [extreme? side]
  (if-not extreme?
    ^{:key (str "more-comments-" side)} [:li.list-group-item [:i.fa.fa-spinner.fa-spin]]))

(defn comment-stream
  []
  (let [scroll-pos (atom nil)
        comments-atom (atom [])
        comments-cont-top (atom 500)]
    (reagent/create-class
     {:component-will-update (fn []
                               (let [scroll-top (->> "el-comments-container"
                                                     (.getElementById js/document)
                                                     .-scrollTop)
                                     first-on-screen-id (->> @comments-atom
                                                             (drop-while #(< (->> %
                                                                                  :db/id
                                                                                  str
                                                                                  (.getElementById js/document)
                                                                                  .-offsetTop)
                                                                             scroll-top))
                                                             first
                                                             :db/id)]
                                 (if first-on-screen-id
                                   (reset! scroll-pos
                                           {:id first-on-screen-id
                                            :offset (- scroll-top (->> first-on-screen-id
                                                                       str
                                                                       (.getElementById js/document)
                                                                       .-offsetTop))})))
                               (reset! comments-cont-top
                                       (->> "el-comments-container"
                                            (.getElementById js/document)
                                            .-offsetTop)))
      :component-did-update (fn []
                              (reset! db/rendered-change? true)
                              (let [comments-cont (->> "el-comments-container" (.getElementById js/document))]
                                (if (and @scroll-pos
                                         (if-let [first-comment (->> @db/first-id (.getElementById js/document))]
                                           (> (.-scrollTop comments-cont)
                                              (- (->> first-comment .getBoundingClientRect .-bottom)
                                                 (.-offsetTop comments-cont)))
                                           true))
                                  (set! (.-scrollTop comments-cont)
                                        (let [{:keys [id offset]} @scroll-pos]
                                          (->> id
                                               str
                                               (.getElementById js/document)
                                               .-offsetTop
                                               (+ offset)))))))
      :reagent-render (fn []
                        (let [scroll-pos (atom nil)
                              {{:keys [render/comments]} :root/render}
                              @(p/pull db/conn [{:root/render [:render/comments]}] 0)]
                          (reset! comments-atom comments)
                          [v-box
                           :attr {:id :el-comments-container
                                  :on-scroll #(re-frame/dispatch [:prepare-select-for-render])}
                           :style {:overflow-y "scroll"
                                   :height (str (- (.. js/document -body -clientHeight)
                                                   @comments-cont-top)
                                                "px")}
                           :children [[:ul#el-comment-root.list-group
                                       (-> (for [{:keys [db/id thread/color]} comments]
                                             ^{:key id} [comment-view id color])
                                           (conj (comment-bookend (= @db/first-id (-> comments first :db/id)) "begin"))
                                           reverse
                                           (conj (comment-bookend (= @db/last-id (-> comments last :db/id)) "end"))
                                           reverse
                                           (#(remove nil? %)))]]]))})))

(defn main-panel []
  (fn []
    [v-box
     :children [[box :child [top-panel]]
                [box :child [comment-stream]]]]))
