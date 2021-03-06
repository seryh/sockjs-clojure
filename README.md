# SockJs for clojure

Fork https://github.com/jenshaase/sockjs-clojure

added sockjs.iowrapper, implements a simple protocol from client https://github.com/seryh/SockJSIO


## Usage

```clojure
(:require [sockjs.iowrapper :as wr]))

(defrecord Connection []
  wr/ISockEvents
  (on-open [this session]
    #_(register-user session))
  (on-message [this session msg]
    (wr/emit session :pong {:foo "bar"}))
  (on-close [this session]
    #_(unregister-user session)))
```

Next we can create an compojure handler:

```clojure
(defroutes my-routes
  (GET "/" [] "hello world")
  (wr/io-handler "/ws" (->Connection) {:response-limit 4096}))
```

