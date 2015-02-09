(ns sockjs.session
  (:require [org.httpkit.server :as server]
            [org.httpkit.timer :as timer]
            [cheshire.core :as json]
            [clojure.string :as cstr]))

(defprotocol SockjsConnection
  (on-open [this session])
  (on-message [this session msg])
  (on-close [this session]))


;; JSON Unicode Encoding
;; =====================
;;
;; SockJS takes the responsibility of encoding Unicode strings for
;; the user. The idea is that SockJS should properly deliver any
;; valid string from the browser to the server and back. This is
;; actually quite hard, as browsers do some magical character
;; translations. Additionally there are some valid characters from
;; JavaScript point of view that are not valid Unicode, called
;; surrogates (JavaScript uses UCS-2, which is not really Unicode).
;;
;; Dealing with unicode surrogates (0xD800-0xDFFF) is quite special.
;; If possible we should make sure that server does escape decode
;; them. This makes sense for SockJS servers that support UCS-2
;; (SockJS-node), but can't really work for servers supporting unicode
;; properly (Python).
;;
;; The server can't send Unicode surrogates over Websockets, also various
;; \u2xxxx chars get mangled. Additionally, if the server is capable of
;; handling UCS-2 (ie: 16 bit character size), it should be able to deal
;; with Unicode surrogates 0xD800-0xDFFF:
;; http://en.wikipedia.org/wiki/Mapping_of_Unicode_characters#Surrogates
(defn escape-json [s]
  (cstr/replace
   s
   (java.util.regex.Pattern/compile
    "[\\x00-\\x1f\\ud800-\\udfff\\u200c-\\u200f\\u2028-\\u202f\\u2060-\\u206f\\ufff0-\\uffff]")
   (fn [match]
     (let [e (str "0000" (Integer/toHexString (int (.charAt match 0))))]
       (str "\\u" (.substring e (- (.length e) 4)))))))

(defn to-json [str]
  (escape-json (json/generate-string str)))

(defn format-message [msg]
  (condp = (:type msg)
    :open "o"
    :close (str "c[" (:close-number msg) ",\"" (:close-reason msg) "\"]")
    :heatbeat "h"
    (str "a[" (if (map? msg)
                (cond
                 (sequential? (:content msg))
                 ,(cstr/join "," (map to-json (:content msg)))
                 :else (to-json (:content msg)))
                (to-json msg))
              "]")))

(defprotocol PSession
  (send! [this msg])
  (close! [this nb reason])
  (unregister-channel [this])
  (register-channel [this channel]))

(defn in-response-limit? [session]
  (let [limit (:response-limit session)
        current @(:bytes-send session)]
    (if (nil? limit)
      true
      (< current limit))))

(declare open-channel?)
(declare remove-session!)

(defn start-heatbeat [session]
  (let [timer-ref (:heatbeat-timer session)]
    (when (not (nil? @timer-ref))
      (timer/cancel @timer-ref))
    (reset! timer-ref (timer/schedule-task
                       (or (:heatbeat-delay session) 25000)
                       (when (open-channel? session)
                         (send! session {:type :heatbeat})
                         (start-heatbeat session))))))

(defn stop-heatbeat [session]
  (let [timer-ref (:heatbeat-timer session)]
    (when (not (nil? @timer-ref))
      (timer/cancel @timer-ref))))

(defn start-disconnect [session]
  (let [timer-ref (:disconnect-timer session)]
    (when (not (nil? @timer-ref))
      (timer/cancel @timer-ref))
    (reset! timer-ref (timer/schedule-task
                       (or (:disconnect-delay session) 5000)
                       (when-not (open-channel? session)
                         (stop-heatbeat session)
                         (remove-session! session))))
    session))

(defn stop-heatbeat [session]
  (let [timer-ref (:disconnect-timer session)]
    (when (not (nil? @timer-ref))
      (timer/cancel @timer-ref))))

(defrecord StreamingSession [channel buffer fmt bytes-send disconnect-timer
                             heatbeat-timer]
  PSession
  
  (send! [this msg]
    (if (and (not (nil? @channel))
             (server/open? @channel)
             (in-response-limit? this))
      (let [msg (-> msg format-message fmt)]
        (server/send! @channel msg false)
        (swap! bytes-send + (count msg))
        (when-not (in-response-limit? this)
          (server/close @channel)))
      (swap! buffer conj msg))
    this)
  
  (close! [this nb reason]
    (when (server/open? @channel)
      (send! this {:type :close
                   :close-number nb
                   :close-reason reason})
      (server/close @channel))
    (on-close
     (:sockjs-handler this)
     (-> this
         (assoc :ready-state :closed)
         (assoc :close-number nb)
         (assoc :close-reason reason))))

  (unregister-channel [this]
    (stop-heatbeat this)
    (reset! channel nil)
    this)

  (register-channel [this ch]
    (reset! channel ch)
    (start-heatbeat this)
    (doseq [m @buffer
            :let [msg (-> m format-message fmt)]]
      (server/send! ch msg false)
      (swap! buffer rest))
    this))

(defrecord PollingSession [channel buffer fmt disconnect-timer]
  PSession

  (send! [this msg]
    (swap! buffer conj msg)
    this)

  (close! [this nb reason]
    (on-close
     (:sockjs-handler this)
     (-> this
         (assoc :ready-state :closed)
         (assoc :close-number nb)
         (assoc :close-reason reason))))

  (unregister-channel [this]
    (reset! channel nil)
    this)
  
  (register-channel [this ch]
    (reset! channel ch) ;; only store to detect duplicate connections
    (if-not (empty? @buffer)
      (let [msgs @buffer
            open-msg (filter #(= (:type %) :open) msgs)
            close-msg (filter #(= (:type %) :close) msgs)
            content-msg (filter #(= (:type %) :msg) msgs)
            content-msg (if (empty? content-msg)
                          content-msg
                          [{:type :msg
                            :content (map :content content-msg)}])
            msgs (concat open-msg content-msg close-msg)]
        (reset! buffer [])
        (server/send! ch (apply str (map (comp fmt format-message) msgs)))
        (server/close ch)
        this)
      this)))

(def initial-data
  {:ready-state :connecting})

(defn create-streaming-session [id fmt & kvs]
  (-> (->StreamingSession (atom nil) (atom []) fmt (atom 0)
                          (atom nil) (atom nil))
      (merge initial-data (apply hash-map kvs))
      (assoc :id id)))

(defn create-polling-session [id fmt & kvs]
  (-> (->PollingSession (atom nil) (atom []) fmt
                        (atom nil))
      (merge initial-data (apply hash-map kvs))
      (assoc :id id)))

(defn assoc-ready-state [session state]
  (assoc session :ready-state state))

(defn close [session nb reason]
  (-> session
      (assoc-ready-state :closed)
      (assoc :close-number nb)
      (assoc :close-reason reason)))

(defn closed? [session]
  (= (:ready-state session) :closed))

(defn open-channel? [session]
  (let [channel @(:channel session)]
    (and (not (nil? channel))
         (server/open? channel))))

;; session storage

(def sessions (atom {}))

(defn session? [id]
  (contains? @sessions id))

(defn register-session [session]
  (swap! sessions assoc (:id session) session))

(defn remove-session! [session]
  (swap! sessions dissoc (:id session)))

(defn ->session [id]
  (@sessions id))

(defn register-new-channel-internal [session channel]
  (if (open-channel? session)
    (do ;; only one open channel is allowed
      (server/send! channel
                    (-> {:type :close
                         :close-number 2010
                         :close-reason "Another connection still open"}
                        (format-message)
                        ((:fmt session))))
      (server/close channel)
      session)
    (do
      (condp = (:ready-state session)
        :connecting (-> session
                        (send! {:type :open})
                        (assoc-ready-state :open)
                        (register-channel channel))
        :closed (do
                  (server/send! channel
                                (-> {:type :close
                                     :close-number (:close-number session)
                                     :close-reason (:close-reason session)}
                                    (format-message)
                                    ((:fmt session))))
                  (server/close channel)
                  ;; remove session if there are no further request
                  (start-disconnect session)
                  session)
        :open (register-channel session channel)))))

(defn register-new-channel! [id channel]
  (swap! sessions update-in [id] #(register-new-channel-internal % channel))
  (->session id))

(defn unregister-channel! [id]
  (swap! sessions update-in [id] #(unregister-channel %)))

(defn update-session! [id f]
  (swap! sessions update-in [id] f)
  (->session id))

(defn send-msg! [id msg]
  (send! (->session id) {:type :msg
                         :content msg}))
