(ns stream-of-redditness-core.color
  (:require [thi.ng.color.core :as col]))

(comment

  (-> "#ff0000"
      col/css
      col/hsla)

  (map #(% (col/as-hsla (col/css "#ffF00F"))) [:h :s :l])

  (+ 1 1)

  (do
    (defn make-fns
      [a]
      (defn f1
        [b]
        (+ a b)))
    (make-fns 1)
    (f1 2))
  (f1 5)

  )

