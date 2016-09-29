(defproject time-tracker "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [http-kit "2.1.18"]
                 [bidi "2.0.11"]
                 [ring "1.5.0"]
                 [cheshire "5.6.3"]]
  :main ^:skip-aot time-tracker.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
