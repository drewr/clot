(ns com.draines.clot.handlers.system
  (:require [com.draines.clot.irc :as clot]))

(defn ->PONG [conn msg host host2 time]
  (clot/reset-pings! conn)
  (clot/log conn (format "PONG %s: %s" host time)))

