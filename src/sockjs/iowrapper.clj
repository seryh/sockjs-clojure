; (c) Seryh Oleg
; https://github.com/seryh/sockjs-clojure

(ns sockjs.iowrapper
  (:use [sockjs.core :only [sockjs-handler]]
        [clojure.pprint])
  (:require [cheshire.core :as json]
            [sockjs.session :as session]))

(defprotocol ISockEvents
  (on-open [this session])
  (on-message [this session msg])
  (on-close [this session]))

(defn decode-msg [msg]
  (let [data (json/parse-string msg)]
    {:event (keyword (nth data 0))
     :data (nth data 1)}))

(defn msg [event data]
  (json/generate-string [event data]))

(defn emit [session event data]
  (when (= (:ready-state session) :open)
    (session/send! session {:type :msg :content (msg event data)})))

(defrecord Connection [handler]
  session/SockjsConnection
  (session/on-open [this session]
    (do (.on-open handler session) session))
  (session/on-message [this session msg]
    (.on-message handler session (decode-msg msg)) session)
  (session/on-close [this session]
    (do (.on-close handler session)
        (session/start-disconnect session))))

(defn io-handler [path handler & [options]]
  (let [Connection (->Connection handler) ]
    (sockjs-handler path Connection options)))
