(ns stream-of-redditness.runner
    (:require [doo.runner :refer-macros [doo-tests]]
              [stream-of-redditness.core-test]))

(doo-tests 'stream-of-redditness.core-test)
