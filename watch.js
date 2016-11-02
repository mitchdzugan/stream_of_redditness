var filewatcher = require('filewatcher');
var touch = require("touch");

var watcher = filewatcher();

// ... or a directory
watcher.add("./core/src/stream_of_redditness_core");

watcher.on('change', function(file, stat) {
    touch("./browser-extension/src/cljs/stream_of_redditness/core.cljs");
    touch("./mobile/src/stream_of_redditness/android/core.cljs");
    touch("./mobile/src/stream_of_redditness/ios/core.cljs");
});
