(ns time-tracker.timers.pubsub-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [<!! put! chan]]
            [time-tracker.fixtures :as fixtures]
            [time-tracker.db :as db]
            [time-tracker.timers.db :as timers.db]
            [time-tracker.projects.test-helpers :as projects.helpers]
            [time-tracker.auth.test-helpers :as auth.test]
            [time-tracker.util :as util]
            [gniazdo.core :as ws]
            [cheshire.core :as json]
            [clojure.spec :as s]
            [time-tracker.timers.spec]))

(use-fixtures :once fixtures/init-db! fixtures/migrate-test-db fixtures/serve-app)
(use-fixtures :each fixtures/isolate-db)

(def connect-url "ws://localhost:8000/timers/ws-connect/")


(deftest start-timer-command
  (let [gen-projects  (projects.helpers/populate-data! {"gid1" ["foo"]
                                                        "gid2" ["goo"]})
        project-id    (get gen-projects "foo")
        timer1        (timers.db/create! (db/connection) project-id "gid1")
        timer2        (timers.db/create! (db/connection)
                                         (get gen-projects "goo")
                                         "gid2")
        current-time  (util/current-epoch-seconds)
        response-chan (chan 1)
        socket        (ws/connect connect-url
                                  :headers    (auth.test/fake-login-headers "gid1")
                                  :on-receive #(put! response-chan (json/decode % keyword)))]
    (try
      (testing "Owned timer"
        (ws/send-msg socket (json/encode
                             {:command      "start-timer"
                              :timer-id     (:id timer1)
                              :started-time current-time}))
        (let [command-response (<!! response-chan)]
          (is (= (:id timer1) (:timer-id command-response)))
          (is (= current-time (:started-time command-response)))))

      (testing "Unowned timer"
        (ws/send-msg socket (json/encode
                             {:command      "start-timer"
                              :timer-id     (:id timer2)
                              :started-time current-time}))
        (let [command-response (<!! response-chan)]
          (is (:error command-response))))

      (finally (ws/close socket)))))

(deftest stop-timer-command
  (let [gen-projects   (projects.helpers/populate-data! {"gid1" ["foo"]
                                                         "gid2" ["goo"]})
        timer1        (timers.db/create! (db/connection)
                                         (get gen-projects "foo")
                                         "gid1")
        timer2        (timers.db/create! (db/connection)
                                         (get gen-projects "goo")
                                         "gid2")
        current-time  (util/current-epoch-seconds)
        stop-time     (+ current-time 7.0)
        response-chan (chan 1)
        socket        (ws/connect connect-url
                                  :headers    (auth.test/fake-login-headers "gid1")
                                  :on-receive #(put! response-chan
                                                     (json/decode % keyword)))]
    (timers.db/start! (db/connection) (:id timer1) current-time)
    (timers.db/start! (db/connection) (:id timer2) current-time)
    (try
      (testing "Owned timer"
        (ws/send-msg socket (json/encode
                             {:command   "stop-timer"
                              :timer-id  (:id timer1)
                              :stop-time stop-time}))
        (let [command-response (<!! response-chan)]
          (is (s/valid? :timers.db/duration (:duration command-response)))
          (is (= 7.0
                 (get-in command-response [:duration :seconds])))))

      (testing "Unowned timer"
        (ws/send-msg socket (json/encode
                             {:command   "stop-timer"
                              :timer-id  (:id timer2)
                              :stop-time stop-time}))
        (let [command-response (<!! response-chan)]
          (is (:error command-response))))
      (finally (ws/close socket)))))

(deftest delete-timer-command
  (let [gen-projects     (projects.helpers/populate-data! {"gid1" ["foo"]
                                                           "gid2" ["goo"]})
        timer1           (timers.db/create! (db/connection)
                                            (get gen-projects "foo")
                                            "gid1")
        timer2           (timers.db/create! (db/connection)
                                            (get gen-projects "goo")
                                            "gid2")
        response-chan    (chan 1)
        socket           (ws/connect connect-url
                                     :headers    (auth.test/fake-login-headers "gid1")
                                     :on-receive #(put! response-chan (json/decode % keyword)))]
    (try
      (testing "Owned timer"
        (testing "Timer exists"
          (ws/send-msg socket (json/encode
                               {:command   "delete-timer"
                                :timer-id  (:id timer1)}))
          (let [command-response (<!! response-chan)]
            (is (:delete? command-response))
            (is (= (:id timer1)
                   (:timer-id command-response)))))

        (testing "Timer does not exist"
          (ws/send-msg socket (json/encode
                               {:command  "delete-timer"
                                :timer-id (+ 5 (:id timer1))}))
          (let [command-response (<!! response-chan)]
            (is (:error command-response)))))

      (testing "Unowned timer"
        (ws/send-msg socket (json/encode
                             {:command   "delete-timer"
                              :timer-id  (:id timer2)}))
        (let [command-response (<!! response-chan)]
          (is (:error command-response))))
      
      (finally (ws/close socket)))))

(deftest create-and-start-timer-command
  (let [gen-projects     (projects.helpers/populate-data! {"gid1" ["foo"]
                                                           "gid2" ["goo"]})
        current-time     (util/current-epoch-seconds)
        response-chan    (chan 1)
        socket           (ws/connect connect-url
                                     :headers    (auth.test/fake-login-headers "gid1")
                                     :on-receive #(put! response-chan (json/decode % keyword)))]
    (try
      (testing "Can track time on project"
        (ws/send-msg socket (json/encode
                             {:command      "create-and-start-timer"
                              :project-id   (get gen-projects "foo")
                              :started-time current-time}))
        (let [command-response (<!! response-chan)]
          (is (= (get gen-projects "foo")
                 (:project-id command-response)))
          (is (:create? command-response))
          (is (= current-time
                 (:started-time command-response)))))

      (testing "Can't track time on project"
        (ws/send-msg socket (json/encode
                             {:command      "create-and-start-timer"
                              :project-id   (get gen-projects "goo")
                              :started-time current-time}))
        (let [command-response (<!! response-chan)]
          (is (:error command-response))))
      
      (finally (ws/close socket)))))

(deftest change-timer-duration-command
  (let [gen-projects     (projects.helpers/populate-data! {"gid1" ["foo"]
                                                           "gid2" ["goo"]})
        timer1           (timers.db/create! (db/connection)
                                            (get gen-projects "foo")
                                            "gid1")
        timer2           (timers.db/create! (db/connection)
                                            (get gen-projects "goo")
                                            "gid2")
        current-time     (util/current-epoch-seconds)
        update-time      (+ current-time 17)
        response-chan    (chan 1)
        socket           (ws/connect connect-url
                                     :headers    (auth.test/fake-login-headers "gid1")
                                     :on-receive #(put! response-chan
                                                        (json/decode % keyword)))]
    (try
      (testing "Owned timer"
        (timers.db/start! (db/connection) (:id timer1) current-time)
        (ws/send-msg socket (json/encode
                             {:command      "change-timer-duration"
                              :timer-id     (:id timer1)
                              :current-time update-time
                              :duration     {:hours   0
                                             :minutes 0
                                             :seconds 37}}))
        (let [command-response (<!! response-chan)]
          (is (= (:id timer1) (:timer-id command-response)))
          (is (= update-time (:started-time command-response)))
          (is (= {:hours   0
                  :minutes 0
                  :seconds 37.0}
                 (:duration command-response)))
          (is (not (:error command-response)))))

      (testing "Unowned timer"
        (timers.db/start! (db/connection) (:id timer2) current-time)
        (ws/send-msg socket (json/encode
                             {:command      "change-timer-duration"
                              :timer-id     (:id timer2)
                              :current-time update-time
                              :duration     {:hours   0
                                             :minutes 0
                                             :seconds 37}}))
        (let [command-response (<!! response-chan)]
          (is (:error command-response))))

      (finally (ws/close socket)))))

(deftest broadcast-test
  (let [gen-projects     (projects.helpers/populate-data! {"gid1" ["foo"]})
        project-id       (get gen-projects "foo")
        {timer-id :id}   (timers.db/create! (db/connection) project-id "gid1")
        current-time     (util/current-epoch-seconds)
        response1        (promise)
        socket1          (ws/connect connect-url
                                     :headers    (auth.test/fake-login-headers "gid1")
                                     :on-receive #(deliver response1
                                                           (json/decode % keyword)))
        response2        (promise)
        socket2          (ws/connect connect-url
                                     :headers    (auth.test/fake-login-headers "gid1")
                                     :on-receive #(deliver response2
                                                           (json/decode % keyword)))
        response3        (promise)
        socket3          (ws/connect connect-url
                                     :headers    (auth.test/fake-login-headers "gid2")
                                     :on-receive #(deliver response3
                                                           (json/decode % keyword)))]
    (try
      (ws/send-msg socket1 (json/encode
                            {:command      "start-timer"
                             :timer-id     timer-id
                             :started-time current-time}))
      (is (= timer-id (get @response1 :timer-id)))
      (is (= current-time (get @response1 :started-time)))

      (testing "All clients with the same gid receive the broadcast"
        (is (= @response1 @response2)))

      (testing "Some other gid should not receive the broadcast"
        (is (= :not-received
               (deref response3 100 :not-received))))
      
      (finally
        (ws/close socket1)
        (ws/close socket2)
        (ws/close socket3)))))
