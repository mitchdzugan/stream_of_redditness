(defproject stream-of-redditness "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.229"]
                 [stream-of-redditness-core "0.1.0-SNAPSHOT"]
                 [reagent "0.6.0"]
                 [re-frame "0.8.0"]
                 [garden "1.3.2"]
                 [ns-tracker "0.3.0"]
                 [bidi "2.0.12"]
                 [day8.re-frame/http-fx "0.1.1"]
                 [day8.re-frame/async-flow-fx "0.0.6"]
                 [re-com "0.9.0"]
                 [com.cemerick/url "0.1.1"]
                 [datascript "0.15.0"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [posh "0.5.4"]
                 [dat-harmony "0.1.3-SNAPSHOT"]]

  :plugins [[lein-cljsbuild "1.1.4"]
            [lein-garden "0.2.8"]]

  :min-lein-version "2.5.3"

  :source-paths ["src/clj"]

  :clean-targets ^{:protect false} ["resources/public/js/compiled" "target"
                                    "test/js"
                                    "resources/public/css"
                                    "chrome-ext/js/compiled"]

  :figwheel {:css-dirs ["resources/public/css"]}

  :garden {:builds [{:id           "screen"
                     :source-paths ["src/clj"]
                     :stylesheet   stream-of-redditness.css/screen
                     :compiler     {:output-to     "resources/public/css/screen.css"
                                    :pretty-print? true}}]}

  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}

  :profiles
  {:dev
   {:dependencies [[binaryage/devtools "0.8.2"]
                   [figwheel-sidecar "0.5.7"]
                   [com.cemerick/piggieback "0.2.1"]]

    :plugins      [[lein-figwheel "0.5.7"]
                   [lein-doo "0.1.7"]
                   [cider/cider-nrepl "0.13.0"]]
    }}

  :cljsbuild
  {:builds
   [{:id           "dev"
     :source-paths ["src/cljs"]
     :figwheel     {:on-jsload "stream-of-redditness.core/mount-root"}
     :compiler     {:main                 stream-of-redditness.core
                    :output-to            "resources/public/js/compiled/app.js"
                    :output-dir           "resources/public/js/compiled/out"
                    :asset-path           "js/compiled/out"
                    :source-map-timestamp true
                    :preloads             [devtools.preload]
                    :external-config      {:devtools/config {:features-to-install :all}}
                    }}

    {:id           "min"
     :source-paths ["src/cljs"]
     :compiler     {:main            stream-of-redditness.core
                    :output-to       "resources/public/js/compiled/app.js"
                    :optimizations   :advanced
                    :closure-defines {goog.DEBUG false}
                    :pretty-print    false}}
    {:id           "chrome-ext"
     :source-paths ["src/cljs"]
     :compiler     {:main            stream-of-redditness.chrome-ext
                    :output-to       "chrome-ext/js/compiled/ext.js"
                    :output-dir      "chrome-ext/js/compiled/out"
                    :externs ["externs/externs.js"]
                    :foreign-libs [{:file "https://cdnjs.cloudflare.com/ajax/libs/markdown.js/0.5.0/markdown.min.js"
                                    :provides ["markdown.js"]}
                                   {:file "https://cdnjs.cloudflare.com/ajax/libs/moment.js/2.15.2/moment.min.js"
                                    :provides ["moment.js"]}
                                   {:file "http://www.myersdaily.org/joseph/javascript/md5.js"
                                    :provides ["md5.js"]}]
                    :optimizations   :whitespace
                    :closure-output-charset "US-ASCII"
                    :pretty-print    true}}

    {:id           "test"
     :source-paths ["src/cljs" "test/cljs"]
     :compiler     {:main          stream-of-redditness.runner
                    :output-to     "resources/public/js/compiled/test.js"
                    :output-dir    "resources/public/js/compiled/test/out"
                    :optimizations :none}}
    ]}

  )
