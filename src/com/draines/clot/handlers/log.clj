(ns com.draines.clot.handlers.log
  (:require [com.draines.clot.irc :as clot]))

(defn ->PONG [conn msg host host2 time]
  (clot/reset-pings! conn)
  (clot/log conn (format "PONG %s: %s" host time)))

(defn ->PRIVMSG [conn msg nick user userhost chan msg]
  (clot/log conn (format "PRIVMSG %s <%s> %s" chan nick msg)))

(defn ->JOIN [conn msg nick user userhost chan]
  (clot/log conn (format "JOIN %s %s %s@%s" chan nick user userhost)))

(defn ->QUIT [conn msg nick user userhost reason]
  (clot/log conn (format "QUIT %s %s@%s: %s" nick user userhost reason)))

(defn ->NICK [conn msg nick user userhost newnick]
  (clot/log conn (format "NICK %s -> %s [%s@%s]" nick newnick user userhost)))

(defn ->MODE [conn msg & args]
  (clot/log conn (format "MODE %s" args)))

(defn ->NOTICE [conn msg & args]
  (clot/log conn (format "(NOTICE) %s" msg)))

(defn ->SERVER [conn msg & args]
  (clot/log conn (format "(SERVER) %s" msg)))

(defn ->UNKNOWN [conn msg & args]
  (clot/log conn (format "(UNKNOWN) %s" msg)))

