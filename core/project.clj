(defproject stream-of-redditness-core "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.229"]
                 [re-frame "0.8.0"]
                 [ns-tracker "0.3.0"]
                 [bidi "2.0.12"]
                 [day8.re-frame/http-fx "0.1.1"]
                 [com.cemerick/url "0.1.1"]
                 [datascript "0.15.0"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [posh "0.5.4"]
                 [dat-harmony "0.1.3-SNAPSHOT"]
                 [thi.ng/color "1.2.0"]]

  :plugins [[lein-cljsbuild "1.1.4"]
            [lein-garden "0.2.8"]]

  :min-lein-version "2.5.3"

  :profiles
  {:dev
   {:dependencies [[binaryage/devtools "0.8.2"]
                   [figwheel-sidecar "0.5.7"]
                   [com.cemerick/piggieback "0.2.1"]]

    :plugins      [[lein-figwheel "0.5.7"]
                   [lein-doo "0.1.7"]
                   [cider/cider-nrepl "0.13.0"]]}})
