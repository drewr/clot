(ns com.draines.clot.main
  (:gen-class)
  (:require [com.draines.clot.irc :as clot]
            [clojure.contrib.str-utils :as str-utils])
  (:use [swank.swank :only [ignore-protocol-version start-server]]
        [clojure.main :only [repl]]))

(defn setup! []
  (clot/start-watcher!)
  (clot/register-handler 'com.draines.clot.handlers.google)
  (clot/register-handler 'com.draines.clot.handlers.tumblr)
  (clot/register-handler 'com.draines.clot.handlers.bandname))

(defn swank! [port]
  (let [stop (atom false)]
    (repl :read (fn [rprompt rexit]
                  (if @stop
                    rexit
                    (do
                      (swap! stop (fn [_] true))
                      `(do
                         (ignore-protocol-version "2009-02-24")
                         (start-server "/tmp/slime-port.txt" :encoding "iso-latin-1-unix" :port ~port)))))
          :need-prompt #(identity false))))

(defn -main [host port nick password & channels]
  (let [port (Integer/parseInt port)
        password (when-not (= "nil" password) password)
        channels (str-utils/re-split #" " (first channels))]
    (setup!)
    (swank! 14005)
    (let [c (clot/log-in host port nick channels password)]
      (Thread/sleep 3000)
      (clot/log c (format "connection errors: %s" (clot/connection-agent-errors c))))))
