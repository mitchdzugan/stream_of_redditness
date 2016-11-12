(ns stream-of-redditness-core.webrtc
  (:require [re-frame.core :as re-frame]))

(defn try-props
  [props]
  (some identity (map #(aget js/window %) props)))

(def rtc-peer-connection-factory
  (try-props ["RTCPeerConnection" "webkitRTCPeerConnection" "mozRTCPeerConnection" "msRTCPeerConnection"]))

(def rtc-session-description
  (try-props ["RTCSessionDescription" "mozRTCSessionDescription" "msRTCSessionDescription"]))

(def rtc-ice-candidate
  (try-props ["RTCIceCandidate" "mozRTCIceCandidate" "msRTCIceCandidate"]))

(defn on-error
  [e]
  (println "==== WEBRTC ERROR ====")
  (println e))

(defn make-json
  [s]
  (let [j (.toJSON s)]
    (if (string? j)
      (.parse js/JSON j)
      j)))

(defn set-local-description
  [send rtc-peer-conn thread-id peer-id offer]
  (.setLocalDescription rtc-peer-conn
                        offer
                        (fn []
                          (send {:type :forward
                                 :peerId peer-id
                                 :threadId thread-id
                                 :message (make-json offer)}))
                        on-error))

(defn make-connection
  [ws thread-id my-id peer-id]
  (let [setup-data-channel (fn [rtc-data-channel]
                             (set! (.-onopen rtc-data-channel)
                                   (fn []
                                     (re-frame/dispatch [:connection-complete thread-id peer-id rtc-data-channel])
                                     (.send rtc-data-channel "Hello World")
                                     (set! (.-onmessage rtc-data-channel) #(re-frame/dispatch [:rtc-message thread-id peer-id %]))))
                             (set! (.-onerror rtc-data-channel) on-error))
        send #(.send ws (->> % clj->js (.stringify js/JSON)))
        rtc-peer-conn (rtc-peer-connection-factory. (clj->js {:iceServers [{:url "stun:stun.services.mozilla.com"}
                                                                           {:url "stun:stun.l.google.com:19302"}]
                                                              :optional [{:RtpDataChannels false}]}))]
    (re-frame/dispatch [:initiating-connection thread-id peer-id rtc-peer-conn])
    (if (< my-id peer-id)
      (do
        (setup-data-channel (.createDataChannel rtc-peer-conn (str thread-id ":" my-id ":" peer-id)))
        (.createOffer rtc-peer-conn
                      (partial set-local-description send rtc-peer-conn thread-id peer-id)
                      on-error)))
    (set! (.-ondatachannel rtc-peer-conn)
          #(setup-data-channel (.-channel %)))
    (set! (.-onicecandidate rtc-peer-conn)
          #(if-let [candidate (some-> % .-candidate)]
             (send {:type :forward
                    :peerId peer-id
                    :threadId thread-id
                    :message {:candidate candidate}})))))

(defn handle-forward-from-peer
  [message ws rtc-peer-conn thread-id peer-id]
  (cond
    (= "offer" (:type message))
    (.setRemoteDescription rtc-peer-conn
                           (rtc-session-description. (clj->js message))
                           (fn []
                             (.createAnswer rtc-peer-conn
                                            (partial set-local-description
                                                     #(.send ws (->> % clj->js (.stringify js/JSON)))
                                                     rtc-peer-conn
                                                     thread-id
                                                     peer-id)
                                            on-error))
                           on-error)
    (= "answer" (:type message))
    (.setRemoteDescription rtc-peer-conn
                           (rtc-session-description. (clj->js message))
                           (fn [] nil)
                           on-error)
    (.-remoteDescription rtc-peer-conn)
    (.addIceCandidate rtc-peer-conn
                      (rtc-ice-candidate. (clj->js (:candidate message))))))
