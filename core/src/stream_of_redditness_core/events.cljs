(ns stream-of-redditness-core.events
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [ajax.core :as ajax]
            [cljs.core.async :refer [put! chan <! >! timeout close!]]
            [cljs.core.match :refer-macros [match]]
            [clojure.data :as data]
            [dat-harmony.core :as dat-harmony]
            [datascript.core :as d]
            [day8.re-frame.http-fx]
            [posh.reagent :as posh]
            [re-frame.core :as re-frame]
            [re-frame.loggers :refer [console]]
            [stream-of-redditness-core.db :as db]
            [stream-of-redditness-core.webrtc :as webrtc]
            [stream-of-redditness-core.util :as util]))

(defn sim
  [chance]
  (< (rand) chance))

(re-frame/reg-cofx
 :datascript
 (fn [coeffects [selector eidf]]
   (assoc coeffects :datascript (d/pull @db/conn selector (eidf (:event coeffects))))))

(re-frame/reg-cofx
 :datascript-db
 (fn [coeffects [selector _]]
   (assoc coeffects :datascript-db @db/conn)))

(re-frame/reg-cofx
 :now
 (fn [coeffects _]
   (assoc coeffects :now (.now js/Date))))

(re-frame/reg-cofx
 :heights
 (fn [coeffects _]
   (assoc coeffects :heights {:display-height (.-innerHeight js/window)
                              :rendered-height (-> js/document
                                                   (.getElementById "el-comments-container")
                                                   (#(if %
                                                        (.-scrollHeight %)
                                                        0))
                                                   )
                              :page-height (-> js/document
                                               (.getElementById "el-comments-container")
                                               (#(if %
                                                   (.-clientHeight %)
                                                   0)))
                              :scroll-top (-> js/document
                                              (.getElementById "el-comments-container")
                                              (#(if %
                                                  (.-scrollTop %)
                                                  0)))})))
(defn handle-transactions
  [{:keys [transactions dont-save async]}]
  (letfn [(create-datoms-at
            [{:keys [datoms path]}]
            (if path
              (let [eid-root (first path)
                    sel (->> path
                             rest
                             reverse
                             (reduce #(assoc {} %2 [%1]) :db/id)
                             (#(-> [%])))
                    pull-res (d/pull @db/conn sel eid-root)
                    eid (or (get-in pull-res (concat (rest path) [:db/id]))
                            (db/tempid))]
                (#(-> [%]) (flatten
                            (concat
                             (if-not pull-res
                               (let [path-init (-> path reverse rest reverse)
                                     path-last (-> path reverse first)]
                                 (if (> (count path-init) 1)
                                   (create-datoms-at
                                    {:datoms [{path-last eid}]
                                     :path path-init})
                                   [{:db/id (first path-init)
                                     path-last eid}])))
                             (map #(assoc % :db/id eid) datoms)))))
              datoms))]
    (let [n (.now js/Date)
          all-datoms (mapcat create-datoms-at transactions)
          first-chan (chan)
          last-chan (if async
                      (reduce (fn [read-chan datom]
                                        (let [write-chan (chan)]
                                          (go (<! read-chan)
                                              (d/transact! db/conn (flatten [datom]))
                                              (>! write-chan true)
                                              )
                                          write-chan)) first-chan all-datoms)
                      (let [c (chan)]
                        (d/transact! db/conn (flatten all-datoms))
                        (go (<! first-chan)
                            (>! c true))
                        c))]
      (go (>! first-chan true)
          (<! last-chan)
          (re-frame/dispatch [:datascript-transact-complete])
          (if-not dont-save (.setItem js/localStorage
                                       "sor-datoms"
                                       (dat-harmony/pull-datoms
                                        d/pull-many @db/conn
                                        [{:root/auth [:auth/flow
                                                      :auth/error
                                                      {:auth/current-user [:user/name
                                                                           :user/token
                                                                           :user/refresh]}
                                                      {:auth/users [:user/name
                                                                    :user/token
                                                                    :user/refresh]}]}]
                                        0)))))))

(defn datascript-interceptor
  "An interceptor which logs data about the handling of an event.
  Includes a `clojure.data/diff` of the db, before vs after, showing
  the changes caused by the event handler.
  You'd typically want this interceptor after (to the right of) any
  path interceptor."
  [debug]
  (re-frame/->interceptor
   :id     :datascript-debug
   :before (fn debug-before
             [context]
             (if debug
               (console :log "Handling re-frame event:" (re-frame/get-coeffect context :event)))
             context)
   :after  (fn debug-after
             [context]
             (let [fdb (fn [db] (update-in db
                                           [:root/polling :polling/threads]
                                           (partial map #(dissoc % :thread/top-level-comments))))
                   event   (re-frame/get-coeffect context :event)
                   orig-db (if debug (fdb (d/pull @db/conn '[*] 0)))
                   transactions (re-frame/get-effect context :datascript-transact ::not-found)]
               (if (= transactions ::not-found)
                 (if debug (console :log "No :db changes caused by:" event))
                 (do
                   (handle-transactions transactions)
                   (if debug
                     (let [new-db (fdb (d/pull @db/conn '[*] 0))
                           [only-before only-after] (data/diff orig-db new-db)
                           db-changed?    (or (some? only-before) (some? only-after))]
                       (if db-changed?
                         (do (console :group "db clojure.data/diff for:" event)
                             (console :log "only before:" only-before)
                             (console :log "only after :" only-after)
                             (console :groupEnd))
                         (console :log "no app-db changes caused by:" event))))))
               (update-in context [:effects] dissoc :datascript-transact)))))

(def interceptors #(-> [(re-frame/inject-cofx :datascript [%1 %2])
                        (re-frame/inject-cofx :datascript-db)
                        (re-frame/inject-cofx :now)
                        ;;(re-frame/inject-cofx :heights)
                        (re-frame/inject-cofx :ws)
                        (datascript-interceptor false)
                        ]))

(defn reg-event-fx
  ([id f] (reg-event-fx id [] #(-> 0) f))
  ([id sel f] (reg-event-fx id sel #(-> 0) f))
  ([id sel eidf f] (re-frame/reg-event-fx id (interceptors sel eidf) f)))

(re-frame/reg-fx
 :listen-storage
 (fn [_]
   (.addEventListener js/window
                      "storage"
                      #(if (=
                            "sor-datoms"
                            (.-key %))
                         (re-frame/dispatch
                          [:storage-update (-> %
                                               .-newValue
                                               cljs.reader/read-string)])))))

(re-frame/reg-fx
 :websocket-send
 (fn [{:keys [ws payload]}]
   (->> payload clj->js (.stringify js/JSON) (.send ws))))

(defn take-while-+1
  ([pred coll]
   (lazy-seq
    (when-let [s (seq coll)]
      (if (pred (first s))
        (cons (first s) (take-while-+1 pred (rest s)))
        (cons (first s) (seq [])))))))

(defn drop-while--1
  [p l]
  (->> l
       reverse
       (take-while-+1 #(not (p %)))
       reverse))

(defn if-using
  ([c tf f] (if-let [r c] (tf r) c))
  ([c tf] (if-let [r c] (tf r))))

(defn last-that-satisfies
  [p l]
  (loop [[[x1 & [x2 & _ :as xs]] store] [l {}]]
    (let [store (merge store
                       {x1 (or (get store x1) (if (p x1) :yes :no))}
                       (if x2
                         {x2 (or (get store x2) (if (p x2) :yes :no))}
                         {}))
          px1 (= :yes (get store x1))
          px2 (= :yes (get store x2))]
      (cond
        (and px1 (nil? xs)) x1
        (and px1 (not px2)) x1
        (not px1) nil
        :else (recur [xs store])))))

(defn print-ret-with
  [f v]
  (println (f v))
  v)

(def print-ret (partial print-ret-with identity))

(defn get-extreme-id
  [chars-per-pixel target-amount-off-screen get-furthest-off-screen rendered-comments all-comments]
  (let [first-rendered-id (-> rendered-comments first :db/id)
        target-delta-size (* (- target-amount-off-screen
                                (-> first-rendered-id get-furthest-off-screen))
                             chars-per-pixel)]
    (if (> target-delta-size 0)
      (->> all-comments
           (take-while #(not= first-rendered-id (:db/id %)))
           reverse
           (reduce (fn [{:keys [target-id size-acc]} {:keys [db/id comment/size]}]
                     (let [curr-size (+ size-acc size)]
                       {:target-id (if (and (not target-id)
                                            (> curr-size target-delta-size))
                                     id
                                     target-id)
                        :size-acc curr-size}))
                   {:size-acc 0})
           :target-id
           (#(or % (-> all-comments first :db/id))))
      (:db/id (or
               (last-that-satisfies #(> (get-furthest-off-screen (:db/id %)) target-amount-off-screen) (drop 3 rendered-comments))
               (first rendered-comments))))))

(def calc-queue (atom false))

(re-frame/reg-fx
 :calculate-for-render
 (fn [{:keys [display-height rendered-height scroll-top db queue-request?]}]
   (let [{{:keys [render/last-char-count] last-rendered :render/comments} :root/render
          {:keys [polling/threads]} :root/polling}
         (d/pull db [{:root/render [:render/last-char-count :render/comments]}
                     {:root/polling [{:polling/threads [{:thread/top-level-comments [:db/id :comment/created :comment/loaded :comment/size]}
                                                        :thread/color]}]}] 0)
         all-comments (->> threads
                           (mapcat (fn [thread]
                                     (->> thread
                                          :thread/top-level-comments
                                          (filter :comment/loaded)
                                          (map #(assoc % :thread/color (:thread/color thread))))))
                           (sort-by #(* -1 (:comment/created %))))]
     (reset! db/first-id (-> all-comments first :db/id))
     (reset! db/last-id (-> all-comments last :db/id))
     (if @db/rendered-change?
       (re-frame/dispatch
        [:commit-for-render
         (if (> last-char-count 0)
           (let [comments-top (->> "el-comments-container"
                                   (.getElementById js/document)
                                   .-offsetTop)
                 chars-per-pixel (/ last-char-count rendered-height)
                 base-target (* display-height 4)
                 adjust-threshold (* display-height 2)
                 distance-calc-top #(- comments-top
                                       (if-using (.getElementById js/document (str %))
                                                 (fn [el] (-> el .getBoundingClientRect .-top))))
                 distance-calc-bottom #(- (if-using (.getElementById js/document (str %))
                                                    (fn [el] (-> el .getBoundingClientRect .-bottom)))
                                          (+ display-height comments-top))
                 prev-first-id (-> last-rendered first :db/id)
                 prev-last-id (-> last-rendered last :db/id)
                 should-adjust? (or (< (distance-calc-top prev-first-id) adjust-threshold)
                                    (< (distance-calc-bottom prev-last-id) adjust-threshold))
                 first-id (if should-adjust?
                            (get-extreme-id chars-per-pixel
                                            base-target
                                            distance-calc-top
                                            last-rendered
                                            all-comments)
                            prev-first-id)
                 last-id (if should-adjust?
                           (get-extreme-id chars-per-pixel
                                           base-target
                                           distance-calc-bottom
                                           (reverse last-rendered)
                                           (reverse all-comments))
                           prev-last-id)
                 comments (->> all-comments
                               (drop-while #(not= (:db/id %) first-id))
                               reverse
                               (drop-while #(not= (:db/id %) last-id))
                               reverse)]
             (if-not (= last-rendered comments)
               (reset! db/rendered-change? false))
             {:char-count (reduce #(+ %1 (:comment/size %2)) 0 comments)
              :comments comments
              :top-whitespace (->> all-comments
                                   (take-while #(not= (:db/id %) first-id))
                                   (reduce #(+ %1 (:comment/size %2)) 0))})
           (let [comments (->> all-comments (take 20))]
             {:comments comments
              :char-count (reduce #(+ %1 (:comment/size %2)) 0 comments)
              :top-whitespace 0}))])
       (if-not queue-request? (reset! calc-queue true))))))


(re-frame/reg-cofx
  :ws
  (fn [coeffects _]
    (assoc coeffects :ws nil)))

(re-frame/reg-fx
 :websocket
 (fn [{:keys [host onmessage]}]
   (let [ws (js/WebSocket. host)]
     (re-frame/reg-cofx
      :ws
      (fn [coeffects _]
        (assoc coeffects :ws ws)))
     (set! (.-onmessage ws) #(re-frame/dispatch [onmessage (clojure.walk/keywordize-keys (js->clj (.parse js/JSON (.-data %))))])))))

(defmulti initial-dispatch :root/view)
(defmethod initial-dispatch :auth [_] [:auth-flow-submit-code])
(defmethod initial-dispatch :stream [_] [:poll-reddit :init])

(reg-event-fx
 :datascript-transact-complete
 (fn [_ _] {}))

(reg-event-fx
 :storage-update
 (fn [_ [_ datoms]]
   {:datascript-transact {:transactions [{:datoms datoms}]
                          :dont-save true}}))

(reg-event-fx
 :initialize-db
 (fn  [_ _]
   (println "Whoah Nelly xD")
   {:db {}
    :datascript-transact {:transactions (db/init-db)}
    :dispatch [:initial-route-dispatch]
    :listen-storage :no-args-needed
    :websocket {:host "ws://localhost:8000"
                :onmessage :websocket-message}
    }))

(reg-event-fx
 :connection-complete
 (fn [_ [_ thread-id peer-id rtc-data-channel]]
   {:datascript-transact {:transactions [{:datoms [{:db/id [:peer/glob-id (str thread-id ":" peer-id)]
                                                    :peer/state :connected
                                                    :peer/introduced? true
                                                    :peer/rtc-data-channel rtc-data-channel}]}]}}))

(re-frame/reg-fx
 :fire-peer-conn-waiter
 (fn [[thread-id peer-id]]
   (go (>! (db/get-in$ @db/conn
                       [:peer/peer-conn-waiter]
                       [:peer/glob-id (str thread-id ":" peer-id)])
           true))))

(reg-event-fx
 :initiating-connection
 (fn [_ [_ thread-id peer-id rtc-peer-conn]]
   {:dispatch-later [{:ms 10000 :dispatch [:check-connection thread-id peer-id]}]
    :fire-peer-conn-waiter [thread-id peer-id]
    :datascript-transact {:transactions [{:datoms [{:db/id [:peer/glob-id (str thread-id ":" peer-id)]
                                                    :peer/state :connecting
                                                    :peer/rtc-peer-conn rtc-peer-conn}]}]}}))

(reg-event-fx
 :check-connection
 [:peer/state]
 (fn [[_ thread-id peer-id]] [:peer/glob-id (str thread-id ":" peer-id)])
 (fn [{{:keys [peer/state]} :datascript} [_ thread-id peer-id]]
   (if-not (= :connected state)
     {:datascript-transact {:transactions [{:datoms [{:db/id [:peer/glob-id (str thread-id ":" peer-id)]
                                                      :peer/state :failed}]}]}}
     {})))

(reg-event-fx
 :rtc-message
 (fn [_ [_ thread-id peer-id event]]
   (println [thread-id peer-id (.-data event)])))

(reg-event-fx
 :connect-peers
 [{:thread/peers [:peer/state :peer/id :peer/thread :peer/glob-id]}]
 (fn [[_ thread]] [:thread/id thread])
 (fn [{{:keys [thread/peers]} :datascript} [_ thread]]
   {:connect-peers (->> peers
                        (remove #(= :connecting (:peer/state %)))
                        (remove #(= :connected (:peer/state %))))}))

(defmulti websocket-handler :type)

(defmethod websocket-handler "your-id" [{:keys [your_id]} ws]
  (re-frame/reg-fx
   :connect-peers
   (fn [peers]
     (doseq [{:keys [peer/glob-id peer/id peer/thread]} peers]
       (webrtc/make-connection ws thread your_id id))))
  {:datascript-transact {:transactions [{:path [0]
                                         :datoms [{:root/webrtc-id your_id}]}]}})

(defmethod websocket-handler "thread-members-all" [{:keys [thread members]} _]
  {:dispatch [:connect-peers thread]
   :datascript-transact {:transactions [{:datoms (concat (map #(-> {:db/id (db/tempid)
                                                                    :peer/glob-id (str thread ":" %)
                                                                    :peer/id %
                                                                    :peer/peer-conn-waiter (chan)
                                                                    :peer/thread thread}) members)
                                                         (map #(-> {:db/id [:thread/id thread]
                                                                    :thread/peers [:peer/glob-id (str thread ":" %)]}) members))}]}})

(defmethod websocket-handler "thread-members-leave" [{:keys [thread member]} _]
  {:dispatch [:connect-peers thread]
   :datascript-transact {:transactions [{:datoms [[:db/retract
                                                   [:thread/id thread]
                                                   :thread/peers
                                                   [:peer/glob-id (str thread ":" member)]]]}]}})

(defmethod websocket-handler "thread-members-join" [{:keys [thread member]} _]
  {:dispatch [:connect-peers thread]
   :datascript-transact {:transactions [{:datoms [{:db/id (db/tempid)
                                                   :peer/glob-id (str thread ":" member)
                                                   :peer/id member
                                                   :peer/thread thread
                                                   :peer/peer-conn-waiter (chan)}
                                                  {:db/id [:thread/id thread]
                                                   :thread/peers [:peer/glob-id (str thread ":" member)]}]}]}})

(re-frame/reg-fx
 :process-forwarded-message
 (fn [[message ws thread-id peer-id]]
   (let [glob-id [:peer/glob-id (str thread-id ":" peer-id)]
         peer-conn-waiter (db/get-in$ @db/conn [:peer/peer-conn-waiter] glob-id)]
     (go (do (<! peer-conn-waiter)
             (>! peer-conn-waiter true)
             (webrtc/handle-forward-from-peer message
                                              ws
                                              (db/get-in$ @db/conn [:peer/rtc-peer-conn] glob-id)
                                              thread-id
                                              peer-id))))))

(defmethod websocket-handler "forward" [{:keys [thread from message]} ws]
  {:process-forwarded-message [message ws thread from]})

(defmethod websocket-handler :default [v]
  (console :log v))

(reg-event-fx
 :websocket-message
 (fn [{:keys [ws]} [_ message]]
   (websocket-handler message ws)))

(reg-event-fx
 :initial-route-dispatch
 [:root/view]
 (fn [{:keys [datascript]}]
   {:dispatch (initial-dispatch datascript)}))

(reg-event-fx
 :add-thread
 (fn [{:keys [ws]} [_ id color]]
   {:datascript-transact {:transactions [{:path [0 :root/polling :polling/threads]
                                          :datoms [{:thread/id id
                                                    :thread/introduced? false
                                                    :thread/color color}]}]}
    :dispatch [:poll-reddit :init]
    :websocket-send {:ws ws
                     :payload {:type :join
                               :thread id}}}))

(reg-event-fx
 :poll-reddit
 [{:root/polling [:polling/is-polling?
                  :polling/polls-since-root
                  {:polling/threads [:thread/id :thread/mores :thread/last-poll]}]}
  {:root/auth [{:auth/current-user [:user/token]}]}]
 (fn [{{{:keys [polling/is-polling? polling/polls-since-root polling/threads]} :root/polling
        {{:keys [user/token]} :auth/current-user} :root/auth} :datascript
       now :now} _ poll-type]
   (if-not (and (= :init poll-type) is-polling?)
     (if-let [thread-to-poll (->> threads
                                  (sort-by :thread/last-poll)
                                  first)]
       (let [polls-since-root (or polls-since-root 0)
             poll-root? (or (not token)
                            (> polls-since-root 3)
                            (empty? (:thread/mores thread-to-poll)))
             [{:keys [children]} mores] (util/pop-when (:thread/mores thread-to-poll) #(not poll-root?))
             api-call (if poll-root?
                        (if token
                          {:method          :get
                           :uri             (str "https://oauth.reddit.com/comments/" (:thread/id thread-to-poll))
                           :response-format (ajax/json-response-format {:keywords? true})
                           :format          :json
                           :params          {:sort "new"}
                           :headers         {:authorization (str "bearer " token)
                                             :content-type "application/json; charset=UTF-8"}
                           :on-success      [:root-reddit-poll-res (:thread/id thread-to-poll)]
                           :on-failure      [:poll-reddit :loop]}
                          {:method          :get
                           :uri             (str "https://www.reddit.com/comments/" (:thread/id thread-to-poll) ".json?sort=new")
                           :response-format (ajax/json-response-format {:keywords? true})
                           :on-success      [:root-reddit-poll-res (:thread/id thread-to-poll)]
                           :on-failure      [:poll-reddit :loop]}
                          )
                        {:method          :get
                         :uri             "https://oauth.reddit.com/api/morechildren/"
                         :response-format (ajax/json-response-format {:keywords? true})
                         :format          :json
                         :params          {:api_type "json"
                                           :children (->> children
                                                          (reduce #(str %1 "," %2) "")
                                                          (#(subs % 1)))
                                           :link_id (str "t3_" (:thread/id thread-to-poll))
                                           :sort "new"}
                         :on-success      [:more-reddit-poll-res (:thread/id thread-to-poll)]
                         :on-failure      [:poll-reddit :loop]
                         :headers         {:authorization (str "bearer " token)
                                           :content-type "application/json; charset=UTF-8"}
                         })]
         {:dispatch [:poll-request api-call]
          :datascript-transact {:transactions [{:path [0 :root/polling]
                                                :datoms [{:polling/is-polling? true
                                                          :polling/polls-since-root (if poll-root?
                                                                                      0
                                                                                      (inc polls-since-root))}]}
                                               {:datoms [{:db/id [:thread/id (:thread/id thread-to-poll)]
                                                          :thread/last-poll now
                                                          :thread/mores mores}]}]}})
       {:datascript-transact {:transactions [{:datoms [{:db/id 0
                                                        :is-polling false}]}]}}))))

(reg-event-fx
 :poll-request
 [{:root/auth [{:auth/current-user [:user/token]}]}
  {:root/polling [:polling/calls-since-poll]}]
 (fn [{{{:keys [polling/calls-since-poll] :or {polling/calls-since-poll 0}} :root/polling
        {{:keys [user/token]} :auth/current-user} :root/auth} :datascript} [_ api-call]]
   (if (= 0 calls-since-poll)
     {:dispatch [:reddit-api-request api-call]}
     {:dispatch-later [{:ms (* (if token 1000 30000) calls-since-poll) :dispatch [:poll-request api-call]}]
      :datascript-transact {:transactions [{:path [0 :root/polling]
                                            :datoms [{:polling/calls-since-poll 0}]}]}})))

(reg-event-fx
 :reddit-api-request
 [{:root/polling [:polling/calls-since-poll]}]
 (fn [{{{:keys [polling/calls-since-poll] :or {polling/calls-since-poll 0}}
        :root/polling} :datascript} [_ api-call]]
   (let [on-failure (:on-failure api-call)]
     {:http-xhrio (merge api-call {:on-failure [:reddit-api-request-failed api-call on-failure]})
      :datascript-transact {:transactions [{:path [0 :root/polling]
                                            :datoms [{:polling/calls-since-poll (inc calls-since-poll)}]}]}})))

(reg-event-fx
 :reddit-api-request-failed
 [{:root/auth [{:auth/current-user [:user/refresh]}]}]
 (fn [{{{{:keys [user/refresh]} :auth/current-user} :root/auth} :datascript}
      [_ api-call on-failure {:keys [response]}]]
   (if (and (= "Unauthorized" (:message response)) (= 401 (:error response)))
     {:dispatch [:reddit-api-request
                 {:method          :post
                  :uri             "https://www.reddit.com/api/v1/access_token"
                  :response-format (ajax/json-response-format {:keywords? true})
                  :body            (str "grant_type=refresh_token"
                                        "&refresh_token=" refresh)
                  :on-success      [:refresh-success api-call on-failure]
                  :on-failure      [:refresh-failed on-failure]
                  :headers         {:authorization "Basic LWtvX2lGTVQxVURLT1E6"
                                    :content-type "application/x-www-form-urlencoded"}}]}
     (if on-failure {:dispatch on-failure} {}))))

(reg-event-fx
 :refresh-success
 [{:root/auth [{:auth/current-user [:user/name]}]}]
 (fn [{{{{:keys [user/name]} :auth/current-user} :root/auth} :datascript}
      [_ api-call on-failure {:keys [error access_token]}]]
   (if error
     {:dispatch on-failure}
     {:datascript-transact {:transactions [{:datoms [{:db/id [:user/name name]
                                                      :user/token access_token}]}]}
      :dispatch [:reddit-api-request (assoc-in api-call
                                               [:headers :authorization]
                                               (str "bearer " access_token))]})))

(reg-event-fx
 :refresh-failed
 (fn [_ _]
   (println "Failed Refresh :(")))

(reg-event-fx
 :commit-for-render
 (fn [_ [_ {:keys [comments char-count top-whitespace]}]]
   (merge (if @calc-queue
            (do (reset! calc-queue false)
                {:dispatch [:prepare-select-for-render true]})
            {})
          {:datascript-transact {:transactions [{:path [0 :root/render]
                                                 :datoms [{:render/last-char-count char-count
                                                           :render/comments comments
                                                           :render/top-whitespace top-whitespace}]}]}})))

(reg-event-fx
 :prepare-select-for-render
 (fn [{{:keys [scroll-top rendered-height display-height]} :heights
       db :datascript-db}
      [_ queue-request?]]
   {:calculate-for-render {:scroll-top scroll-top
                           :rendered-height rendered-height
                           :display-height display-height
                           :db db
                           :queue-request? queue-request?}}))

(defn process-comments
  [mores thread-id res root-path]
  (let [c (chan)
        all-data (reduce (partial util/comment-to-datoms c)
                         []
                         (get-in res root-path))
        pred #(= :more (:kind %))
        [new-mores datoms] [(filter pred all-data) (remove pred all-data)]]
    (go (>! c false))
    (go-loop [comments []]
      (if-let [comment (<! c)]
        (recur (conj comments comment))
        (go-loop [[[comment-group & comment-groups] transactions tl-comment-sizes-before] [(partition-all 20 comments) [] {}]]
          (if comment-group
            (let [results (d/q '[:find ?hash
                                 :in $ [?hash ...]
                                 :where [_ :markdown/hash ?hash]]
                               @db/conn
                               (map (fn [[id _ h]] (str h id)) comment-group))
                  original-sizes (->> comment-group
                                      (map (fn [[id _ _]] id))
                                      (d/q '[:find ?eid ?size
                                             :in $ [?cid ...]
                                             :where
                                             [?mid :markdown/size ?size]
                                             [?eid :comment/markdown ?mid]
                                             [?eid :comment/id ?cid]]
                                           @db/conn)
                                      (into {}))
                  reverse-comment-tree (->> comment-group
                                            (map (fn [[id _ h]] id))
                                            (d/q '[:find (pull ?eid [:comment/size
                                                                     :comment/id
                                                                     {:comment/_children ...}])
                                                   :in $ [?cid ...]
                                                   :where [?eid :comment/id ?cid]]
                                                 @db/conn)
                                            flatten)
                  tl-comment-lookup (->> reverse-comment-tree
                                         (map #(loop [{:keys [comment/id comment/_children]} %]
                                                 (if _children
                                                   (recur _children)
                                                   {(:comment/id %) id})))
                                         (reduce merge {}))
                  new-comments (->> comment-group
                                    (remove (fn [[id _ h]] (contains? results [(str h id)]))))
                  new-parsed (->> new-comments
                                  (map (fn [[id body h]]
                                         (let [parsed (js->clj (.parse (.-markdown js/window) body "Maruku"))]
                                           {:comment/id id
                                            :markdown/parsed parsed
                                            :markdown/size (count (str parsed))
                                            :markdown/hash (str h id)}))))
                  tl-comment-sizes (reduce (fn [tl-c-s {:keys [comment/id markdown/size]}]
                                             (let [delta (- size (or (get original-sizes id) 0))
                                                   tl-c (get tl-comment-lookup id)]
                                               (merge tl-c-s
                                                      {tl-c (+ (or (get tl-c-s tl-c) 0) delta)})))
                                           (merge
                                            (->> reverse-comment-tree
                                                 (map #(loop [{:keys [comment/id comment/_children comment/size]} %]
                                                         (if _children
                                                           (recur _children)
                                                           {id size})))
                                                 (reduce merge {}))
                                            tl-comment-sizes-before)
                                           new-parsed)
                  updated-transactions (concat transactions [{:datoms (map #(-> %
                                                                                (assoc :db/id (db/tempid))
                                                                                (dissoc :comment/id))
                                                                           new-parsed)}
                                                             {:datoms (->> comment-group
                                                                           (map (fn [[id _ h]]
                                                                                  {:db/id [:comment/id id]
                                                                                   :comment/markdown [:markdown/hash (str h id)]
                                                                                   :comment/loaded true})))}])]
              (<! (timeout (+ 50 (* 5 (count new-comments)))))
              (recur [comment-groups updated-transactions tl-comment-sizes]))
            (re-frame/dispatch [:add-completed-comments
                                (conj
                                 transactions
                                 {:datoms (map (fn [[id size]]
                                                 {:db/id [:comment/id id]
                                                  :comment/size size}) tl-comment-sizes-before)})
                                thread-id])))))
    {:datascript-transact
     {:transactions [{:datoms datoms}
                     {:datoms [{:db/id [:thread/id thread-id]
                                :thread/mores (concat mores new-mores)}]}
                     {:datoms [(->> datoms
                                    (remove #(nil? (:comment/parent %)))
                                    (map #(if (= (str "t3_" thread-id)
                                                 (:comment/parent %))
                                            {:db/id [:thread/id thread-id]
                                             :thread/top-level-comments [:comment/id (:comment/id %)]}
                                            {:db/id [:comment/id (subs (:comment/parent %) 3)]
                                             :comment/children [:comment/id (:comment/id %)]})))]}]
      :async false}}))


(reg-event-fx
 :root-reddit-poll-res
 [:thread/mores :thread/last-poll]
 (fn [[_ thread-id _]] [:thread/id thread-id])
 (fn [{{:keys [thread/mores]} :datascript} [_ thread-id res]]
   (process-comments mores thread-id res [1 :data :children])))

(reg-event-fx
 :more-reddit-poll-res
 [:thread/mores :thread/last-poll]
 (fn [[_ thread-id _]] [:thread/id thread-id])
 (fn [{{:keys [thread/mores]} :datascript} [_ thread-id res]]
   (process-comments mores thread-id res [:json :data :things])))

(re-frame/reg-fx
 :share-new-comments
 (fn [[new-comments thread-id]]
   (doseq [comment (d/q '[:find (pull ?cid [:comment/id :comment/author
                                            :comment/created :comment/score
                                            {:comment/markdown [:markdown/parsed]}])
                          :in $ [?hash ...]
                          :where
                          [?mid :markdown/hash ?hash]
                          [?cid :comment/markdown ?mid]]
                        @db/conn
                        new-comments)]
     (println (d/pull @db/conn [{:thread/peers [:peer/state :peer/rtc-data-channel]}] [:thread/id thread-id]))
     (doseq [channel (->>
                      (d/pull @db/conn [{:thread/peers [:peer/state :peer/rtc-data-channel]}] [:thread/id thread-id])
                          :thread/peers
                          (filter #(= :connected (:peer/state %)))
                          (map :peer/rtc-data-channel))]
       (.send channel (.stringify js/JSON (clj->js comment)))))))

(reg-event-fx
 :add-completed-comments
 (fn [_ [_ transactions thread-id]]
   (let [new-comments
         (reduce (fn [new-comments transaction]
                   (let [potential-new-comments (->> transaction
                                                     :datoms
                                                     (filter :markdown/hash)
                                                     (map :markdown/hash))
                         already-seen-comments (d/q '[:find ?hash
                                                      :in $ [?hash ...]
                                                      :where [_ :markdown/hash ?hash]]
                                                    @db/conn
                                                    potential-new-comments)]
                     (concat new-comments (remove #(contains? already-seen-comments [%]) potential-new-comments))))
                 []
                 transactions)]
     {:share-new-comments [new-comments thread-id]
      :dispatch-later [{:ms 100 :dispatch [:poll-reddit :loop]}
                       {:ms 0 :dispatch [:prepare-select-for-render]}]
      :datascript-transact {:transactions transactions}})))

(reg-event-fx
 :reddit-auth-error
 (fn [_ v]
   {:datascript-transact {:transactions [{:path [0 :root/auth]
                                          :datoms [{:auth/flow :error
                                                    :auth/error v}]}]}}))

(reg-event-fx
 :auth-flow-begin
 (fn [_ _]
   {:datascript-transact {:transactions [{:path [0 :root/auth]
                                          :datoms [{:auth/flow :begin}]}]}}))

(reg-event-fx
 :auth-flow-submit-code
 [:code]
 (fn [{:keys [datascript]} _]
   {:datascript-transact {:transactions [{:path [0 :root/auth]
                                          :datoms [{:auth/flow :submit-code}]}]}
    :http-xhrio {:method          :post
                 :uri             "https://www.reddit.com/api/v1/access_token"
                 :response-format (ajax/json-response-format {:keywords? true})
                 :body            (str
                                   "grant_type=authorization_code"
                                   "&code=" (:code datascript)
                                   "&redirect_uri=https://www.reddit.com/r/StreamReddit/auth")
                 :on-success      [:auth-flow-token-success]
                 :on-failure      [:reddit-auth-error]
                 :headers         {:authorization "Basic LWtvX2lGTVQxVURLT1E6"
                                   :content-type "application/x-www-form-urlencoded"}}}))

(reg-event-fx
 :auth-flow-token-success
 (fn [_ [_ {:keys [error access_token refresh_token]}]]
   (if error
     {:dispatch [:reddit-auth-error error]}
     {:datascript-transact {:transactions [{:path [0 :root/auth]
                                            :datoms [{:auth/flow :get-me}]}]}
      :http-xhrio {:method          :get
                   :uri             "https://oauth.reddit.com/api/v1/me"
                   :response-format (ajax/json-response-format {:keywords? true})
                   :on-success      [:auth-flow-me-success access_token refresh_token]
                   :on-failure      [:reddit-auth-error]
                   :headers         {:authorization (str "bearer " access_token)}}})))

(reg-event-fx
 :auth-flow-me-success
 (fn [_ [_ token refresh {:keys [name]}]]
   {:datascript-transact {:transactions [{:datoms [{:db/id (db/tempid)
                                                    :user/name name
                                                    :user/token token
                                                    :user/refresh refresh}]}
                                         {:path [0 :root/auth]
                                          :datoms [{:auth/flow :complete
                                                    :auth/current-user [:user/name name]
                                                    :auth/users [:user/name name]}]}]}}))

(reg-event-fx
 :switch-account
 (fn [_ [_ id]]
   {:datascript-transact {:transactions [{:path [0 :root/auth]
                                          :datoms [{:auth/current-user id}]}]}}))

