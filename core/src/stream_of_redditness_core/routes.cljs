(ns stream-of-redditness-core.routes
    (:require [bidi.bidi :as bidi]))

(def my-routes ["/r/StreamReddit/" {"auth" :auth
                                    "stream/" {[:threads ""] :stream}}])

(def match-route (partial bidi/match-route my-routes))

(def path-for (partial bidi/path-for my-routes))
