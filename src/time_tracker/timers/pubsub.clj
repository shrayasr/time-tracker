(ns time-tracker.timers.pubsub
  (:require [org.httpkit.server :refer [send!]]
            [cheshire.core :as json]
            [time-tracker.timers.db :as timers.db]
            [time-tracker.util :as util]
            [clojure.spec :as s]
            [time-tracker.timers.spec]))

;; A map of google-ids to sets of channels.
(defonce active-connections (atom {}))

(defn- conj-to-set
  "If the-set is nil, returns a new set containing value.
  Behaves like conj otherwise."
  [the-set value]
  (if (nil? the-set)
    #{value}
    (conj the-set value)))

(defn broadcast-to!
  "Serializes data and sends it to all connections belonging to
  google-id."
  [google-id data]
  (let [str-data (json/encode data)]
    (doseq [channel (get @active-connections google-id)]
      (send! channel str-data))))

(defn start-timer-command!
  [channel google-id {:keys [timer-id started-time] :as args}]
  (if-not (s/valid? :timers.pubsub/start-timer-args args)
    (send! channel (json/encode {:error "Invalid args"}))
    (if-let [{started-time :started_time duration :duration}
             (timers.db/start-if-authorized! timer-id started-time google-id)]
      (broadcast-to! google-id
                     {:timer-id     timer-id
                      :started-time (util/to-epoch-seconds started-time)
                      :duration     duration})
      (send! channel (json/encode
                      {:error "Could not start timer"})))))

(def command-map
  {"start-timer" start-timer-command!})

(defn add-channel!
  "Adds a channel to the map of active connections."
  [channel google-id]
  (swap! active-connections update google-id conj-to-set channel))

(defn on-close!
  "Called when a channel is closed."
  [channel google-id status]
  ;; TODO: Use status in logging
  (swap! active-connections update google-id disj channel))

(defn dispatch-command!
  "Calls the appropriate timer command."
  [channel google-id command-data]
  (if-let [command-fn (command-map (get command-data :command))]
    (command-fn channel google-id (dissoc command-data :command))
    ;; TODO: Add error logging/better error response
    (send! channel (json/encode
                    {:error "Invalid command"}))))


