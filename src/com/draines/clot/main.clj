(ns com.draines.clot.main
  (:gen-class)
  (:require [com.draines.clot.irc :as clot]
            [com.draines.clot.handlers log google]
            [clojure.contrib.str-utils :as str-utils]))

(defn setup! []
  (clot/start-watcher!)
  (clot/register-handler 'com.draines.clot.handlers.log)
  (clot/register-handler 'com.draines.clot.handlers.google))

(defn -main [host port nick password & channels]
  (let [port (Integer/parseInt port)
        password (when-not (= "nil" password) password)
        channels (str-utils/re-split #" " (first channels))]
    (setup!)
    (let [c (clot/log-in host port nick channels password)]
      (Thread/sleep 3000)
      (clot/log (clot/connection-agent-errors c)))))
