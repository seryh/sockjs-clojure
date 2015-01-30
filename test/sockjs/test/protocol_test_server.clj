(ns sockjs.test.protocol-test-server
  (:use [clojure.java.shell :only [sh]]
        [compojure.core :only [defroutes GET POST DELETE ANY]]
        [compojure.handler :only [site]]
        [ring.middleware.params :only [wrap-params]]
        [ring.middleware.reload :only [wrap-reload]]
        [sockjs.core :only [sockjs-handler]]
        clojure.test
        org.httpkit.server)
  (:require [compojure.route :as route]
            [sockjs.session :as session]))

(defrecord EchoConnection []
  session/SockjsConnection
  (session/on-open [this s] s)
  (session/on-message [this s msg]
    (session/send! s {:type :msg :content msg}))
  (session/on-close [this s] s))

(defrecord CloseConnection []
  session/SockjsConnection
  (session/on-open [this s]
    (session/close! s 3000 "Go away!"))
  (session/on-message [this s msg] s)
  (session/on-close [this s] s))

(defroutes all-routes
  (GET "/" [] "hello world")
  (sockjs-handler "/echo" (->EchoConnection) {:response-limit 4096})
  (sockjs-handler "/close" (->CloseConnection) {:response-limit 4096})
  (sockjs-handler "/disabled_websocket_echo" (->EchoConnection)
                  {:response-limit 4096
                   :websocket false})
  (sockjs-handler "/cookie_needed_echo" (->EchoConnection)
                  {:response-limit 4096
                   :jsessionid true})
  (route/not-found "<p>Page not found.</p>"))

(defn start-server []
  (run-server
   (-> all-routes
       (wrap-params)
       (wrap-reload)
       site)
   {:port 8081}))

;; use this for development
(defn -main []
  (start-server)
  (println "Protocol test server started."))

(deftest run-python-protocol-test
  (let [server (start-server)]
    (try
      (println
       (sh "./run-sockjs-protocol-test.sh"
           :dir "test/sockjs/test"))
      (is true)
      (catch Exception e
        (is false))
      (finally
        ;; stop server
        (server :timeout 100)))))

