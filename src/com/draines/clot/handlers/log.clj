(ns com.draines.clot.handlers.log
  (:require [com.draines.clot.irc :as clot]))

(defn ->PONG [conn args]
  (let [[host host2 time] args]
    (clot/reset-pings! conn)
    (clot/log conn (format "PONG %s: %s" host time))))

(defn ->PRIVMSG [conn args]
  (let [[nick user userhost chan msg] args]
    (clot/log conn (format "PRIVMSG %s <%s> %s" chan nick msg))))

(defn ->JOIN [conn args]
  (let [[nick user userhost chan] args]
    (clot/log conn (format "JOIN %s %s %s@%s" chan nick user userhost))))

(defn ->QUIT [conn args]
  (let [[nick user userhost reason] args]
    (clot/log conn (format "QUIT %s %s@%s: %s" nick user userhost reason))))

(defn ->NICK [conn args]
  (let [[nick user userhost newnick] args]
    (clot/log conn (format "NICK %s -> %s [%s@%s]" nick newnick user userhost))))

(defn ->MODE [conn args]
  (clot/log conn (format "MODE %s" args)))
