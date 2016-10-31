(ns stream-of-redditness.chrome-ext
  (:require [stream-of-redditness.core :as core]))

(set! (.. js/document
          -body
          -innerHTML)
      "<div id='app'></div>")

(defn clear-head
  []
  (let [head (-> js/document
                 (.getElementsByTagName "head")
                 (.item 0))]
    (letfn [(remove-item []
              (if-let [first-child (.-firstChild head)]
                (do (.removeChild head first-child)
                    (remove-item))))]
      (remove-item))))

(defn add-stylesheet
  [href]
  (let [fref (.createElement js/document "link")]
    (.setAttribute fref "rel" "stylesheet")
    (.setAttribute fref "type" "text/css")
    (.setAttribute fref "href" href)
    (.appendChild (.item (.getElementsByTagName js/document "head") 0) fref)))

(clear-head)
(add-stylesheet "https://cdnjs.cloudflare.com/ajax/libs/twitter-bootstrap/3.3.5/css/bootstrap.css")
(add-stylesheet "https://res.cloudinary.com/mitchdzugan/raw/upload/v1477459061/re-com_rrfpmh.css")

(core/init)
