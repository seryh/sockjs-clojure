# SockJs for clojure

This is a sockjs implementation on top of the http-kit server.

## Installation

Add the following dependency to your `project.clj` file:

[![Clojars Project](http://clojars.org/sockjs-clojure/latest-version.svg)](http://clojars.org/sockjs-clojure)

## Usage

You are required to implement the
`sockjs.session.session.SockjsConnection` protocol.  Here is simple
echo implementation. Make sure that each method returns the session
object.

```
(use 'sockjs.session)

(defrecord EchoConnection []
  SockjsConnection

  ;; on open is call whenever a new session is initiated.
  (on-open [this session] session)

  ;; on message is call when a new message arrives at the server.
  (on-message [this session msg]
    (send! s {:type :msg :content msg}))

  ;; when a connection closes this method is called
  (on-close [this session] session))
```

Next we can create an compojure handler:

```
(use 'compojure.core 'sockjs.core)

(defroutes my-routes
  (GET "/" [] "hello world")
  (sockjs-handler "/echo" (->EchoConnection) {:response-limit 4096}))
```

We are now ready to run a http-kit server:

```
(use 'org.httpkit.server 'ring.middleware.params)

(run-server (-> my-routes (wrap-params)) {:port 8000})
```

__NOTE:__ Please do not forget the ring `params` middleware.


For a complete working example have a look at
`test/sockjs/test/protocol_test_server.sh`

## Testing

The server is tested with the
[Sockjs-Protocol](https://github.com/sockjs/sockjs-protocol) test
suite. Running `lein test` will automatically check out the
repository, start the server and run the suite. Currently not all test
pass. These fail because `http-kit` only supports the most recent
Websocket implementation.

For development you can start the server with
`lein run -m sockjs.test.protocol-test-server` and execute the test manually with
`./target/sockjs-protocol/venv/bin/python ./target/sockjs-protocol/sockjs-protocol-0.3.3.py`.

For more info and all requirements to execute the test have a look at
the [Sockjs-Protocol](https://github.com/sockjs/sockjs-protocol) test
suite repository.

## License

Copyright © 2014 Jens Haase

Distributed under the Eclipse Public License, the same as Clojure.
