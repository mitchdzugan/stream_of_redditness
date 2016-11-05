(ns stream-of-redditness.core
    (:require [reagent.core :as reagent]
              [re-frame.core :as re-frame]
              [stream-of-redditness-core.events]
              [stream-of-redditness.views :as views]
              [stream-of-redditness.config :as config]
              [markdown.js]
              [moment.js]
              [md5.js]
              [devtools.core :as devtools]))

(defn dev-setup []
  (when config/debug?
    (do
      (devtools/install!)
      (println "dev mode"))))

(defn mount-root []
  (reagent/render [views/main-panel]
                  (.getElementById js/document "app")))

(defn ^:export init []
  (enable-console-print!)
  (dev-setup)
  (re-frame/dispatch-sync [:initialize-db])
  (set! (.-onresize js/window) #(re-frame/dispatch [:on-scroll]))
  (mount-root))
