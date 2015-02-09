(defproject sockjs-clojure "0.1.1-SNAPSHOT"
  :description "A sockjs implementation on top of http-kit server"
  :url "https://github.com/jenshaase/sockjs-clojure"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :source-paths ["src"]
  :test-paths ["test"]

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [http-kit "2.1.16"]
                 [cheshire "5.4.0"]
                 [compojure "1.3.1"]]

  :profiles {:dev {:dependencies [[ring/ring-devel "1.3.2"]
                                  [javax.servlet/servlet-api "2.5"]
                                  [org.clojure/clojurescript "0.0-2411"]]}})
