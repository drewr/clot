(ns com.draines.clot.irc
  (:require [clojure.contrib.str-utils :as s-util])
  (:import [java.util Date UUID]
           [java.util.regex Pattern]
           [java.text SimpleDateFormat]
           [java.net Socket SocketException]
           [java.io InputStreamReader BufferedReader OutputStreamWriter BufferedWriter FileWriter]
           [java.util.concurrent LinkedBlockingQueue]))

(def *logfile* "/tmp/clot.log")
(def *logger* (agent *logfile*))
(def *stdout* (agent nil))
(def *channels* ["##clot-test"])
(def *keepalive-frequency* 45)
(def *use-console* false)
(def *max-failed-pings* 3)
(def *watcher-interval* 60)
(def *send-delay* 1)
(def *watch* (atom true))
(defonce *next-id* (atom 1))
(defonce *connections* (atom []))

(def connect)

(defn append-file [filename s]
  (let [timestamp (.format (SimpleDateFormat. "yyyy-MM-dd HH:mm:ss.SSS")
                           (java.util.Date.))]
    (with-open [w (FileWriter. filename true)]
      (.write w (format "%s %s" timestamp s)))
    filename))

(defn connection-id [conn]
  (:id conn))

(defn connection-uuid [conn]
  (.toUpperCase (str (:uuid conn))))

(defn connection-uuid-short [conn]
  (re-find #"^.{4}" (connection-uuid conn)))

(defn connection-name [conn]
  (format "%s@%s/%s" (:nick conn) (:host conn) (connection-uuid-short conn)))

(defn uuid->connection [id]
  (if (map? id)
    id
    (let [id (.toUpperCase (str id))
          pat (Pattern/compile (format "^%s" id))
          matches (fn [conn]
                    (when (re-find (.matcher pat (connection-uuid conn)))
                      conn))]
      (some matches @*connections*))))

(defn connection [id]
  (if (map? id)
    id
    (some #(if (= id (:id %)) % nil) @*connections*)))

(defn same-connection? [c1 c2]
  (= (connection-uuid c1) (connection-uuid c2)))

(defn connection-established? [conn]
  (contains? conn :created))

(defn outgoing-queues []
  (map #(deref (:outq %)) @*connections*))

(defn incoming-queues []
  (map #(deref (:inq %)) @*connections*))

(defn append-stdout [x s]
  (print s)
  (.flush *out*))

(defn log
  ([s]
     (let [_s (format "*** %s\n" s)]
       (when *use-console*
         (send-off *stdout* append-stdout _s))
       (send-off *logger* append-file _s)))
  ([conn s]
     (let [id (connection-id conn)]
       (log (format "[%d] %s" id s)))))

(defn now []
  (Date.))

(defn uptime [conn]
  (let [then (.getTime (:created conn))
        now (System/currentTimeMillis)]
    (int (/ (- now then) 1000))))

(defn add-incoming-message [conn msg]
  (.put (:q @(:inq conn)) msg))

(defn add-outgoing-message [conn msg]
  (.put (:q @(:outq conn)) msg))

(defn sendmsg [conn msg]
  (add-outgoing-message conn msg))

(defn stop-incoming-queue [conn]
  (add-incoming-message conn "stop"))

(defn stop-outgoing-queue [conn]
  (add-outgoing-message conn "stop"))

(defn toggle [_atom]
  (swap! _atom #(not %)))

(defn atom-set! [_atom value]
  (swap! _atom (fn [x] value)))

(defn atom-dec! [a]
  (swap! a dec))

(defn atom-inc! [a]
  (swap! a inc))

(defn inc-pings! [conn]
  (atom-inc! (:pings conn)))

(defn reset-pings! [conn]
  (atom-set! (:pings conn) 0))

(defn register-connection [conn]
  (swap! *connections* conj conn))

(defn unregister-connection [conn]
  (swap! *connections*
         (fn [xs]
           (filter #(not (same-connection? conn %)) xs))))

(defn connection-agent-errors [conn]
  (reduce #(conj %1 %2) {} (for [[k v] (select-keys conn [:listener :outq :inq])]
                             {k (agent-errors v)})))

(defn connection-agent-errors? [conn]
  (some identity (vals (connection-agent-errors conn))))

(defn alive? [conn]
  (let [r (:pings conn)
        s (:sock conn)]
    (when (and r s)
      (and
       (not (connection-agent-errors? conn))
       (not (.isClosed s))
       (not (.isInputShutdown s))
       (< @r *max-failed-pings*)))))

(defn dead? [conn]
  (not (alive? conn)))

(defn reconnect? [conn]
  @(:reconnect! conn))

(defn reconnect [conn]
  (swap! (:reconnect! conn) (fn [x] true)))

(defn quit [conn & do-not-reconnect]
  (let [_conn (connection conn)]
    (when (alive? _conn)
      (log _conn (format "shutting down: %s" (connection-name _conn)))
      (stop-incoming-queue _conn)
      (stop-outgoing-queue _conn)
      (.close (:sock _conn)))
    (unregister-connection _conn)))

(defn quit-all []
  (doseq [conn @*connections*]
    (quit conn)))

(defn get-reader [sock]
  (BufferedReader. (InputStreamReader. (.getInputStream sock))))

(defn get-writer [sock]
  (BufferedWriter. (OutputStreamWriter. (.getOutputStream sock))))

(defn sendmsg! [conn line]
  (.write (:writer conn) (format "%s\r\n" line))
  (.flush (:writer conn))
  (log conn (format "-> %s" line)))

(defn do-PONG [conn]
  (reset-pings! conn))

(defn parse-msg! [conn msg]
  (cond
   (re-find #" PONG " msg) (do-PONG conn)))

(defn dispatch [conn line]
  (log conn line)
  (parse-msg! conn line))

(defn ping [conn]
  (sendmsg! conn (format "PING %d" (int (/ (System/currentTimeMillis) 1000))))
  (inc-pings! conn))

(defn reconnectable? [conn]
;  (and @(:reconnect conn)
;       (< 0 @(:remain conn)))
  true)

(defn connection-statuses []
  (map #(format "%s: %s"
                (connection-uuid-short %)
                (if (alive? %) (format "UP %d" (uptime %)) "DOWN")) @*connections*))

(defn make-queue [conn _dispatch & sleep]
  (let [f (fn resend [queue]
            (let [el (.take (:q queue))]
              (when-not (= "STOP" (.toUpperCase (str el)))
                (_dispatch conn el)
                (when sleep
                  (Thread/sleep (* 1000 *send-delay*)))
                (send-off *agent* resend)))
            queue)]
    (log conn (format "start queue %s" _dispatch))
    (send-off (agent {:q (LinkedBlockingQueue.)}) f)))

(defn make-queue1 [conn _dispatch & sleep]
  (let [f (fn [queue]
            (log conn (format "start queue %s" _dispatch))
            (loop []
              (let [el (.take (:q queue))]
                (when-not (= "STOP" (.toUpperCase (str el)))
                  (_dispatch conn el)
                  (when sleep
                    (Thread/sleep (* 1000 *send-delay*)))
                  (recur))))
            (log conn (format "stop queue %s" _dispatch))
            :stopped)]
    (send-off (agent {:q (LinkedBlockingQueue.)}) f)))

(defn keep-alive [conn]
  (let [f (fn resend [c]
            (when (alive? c)
              (Thread/sleep (* 1000 *keepalive-frequency*))
              (ping c)
              (send-off *agent* resend)
              c))]
    (log conn "starting keep-alive")
    (send-off (agent conn) f)))

(defn listen [conn]
  (log conn (format "listening on %s:%d" (:host conn) (.getLocalPort (:sock conn))))
  (send-off
   (agent conn)
   (fn resend [_conn]
     (let [is-connected (atom true)]
       (binding [*in* (:reader _conn)]
         (let [line (try
                     (read-line)
                     (catch SocketException e
                       (toggle is-connected)
                       {:exception e}))]
           (if (and @is-connected line)
             (do
               (add-incoming-message _conn line)
               (send-off *agent* resend)
               _conn)
             line)))))))

(defn connect [info]
  (let [{:keys [host port nick]} info
        sock (Socket. host port)
        _conn (merge (sorted-map :id (atom-inc! *next-id*)
                                 :uuid (UUID/randomUUID)
                                 :sock sock
                                 :reader (get-reader sock)
                                 :writer (get-writer sock)
                                 :pings (atom 0) ; unanswered
                                 :reconnect! (atom false)
                                 :pinger nil
                                 :inq nil
                                 :outq nil
                                 :listener nil
                                 :created nil) info)
        add-in-queue (fn [m] (merge m {:inq (make-queue m dispatch)}))
        add-out-queue (fn [m] (merge m {:outq (make-queue m sendmsg! :sleep)}))
        add-pinger (fn [m] (merge m {:pinger (keep-alive m)}))
        add-listener (fn [m] (merge m {:listener (listen m)}))]
    (log _conn (format "connecting to %s:%d" host port))
    (sendmsg! _conn (format "NICK %s" nick))
    (sendmsg! _conn (format "USER foo 0 * :0.1"))
    (binding [*in* (:reader _conn)]
      (loop [line (read-line)
             _nick nick]
        (when line
          (dispatch _conn line)
          (if-let [codematch (re-find #"^:[^\s]+ (\d\d\d)" line)]
            (let [code (second codematch)]
              (cond
               (= code "433") (let [n (str _nick "-")]
                                (sendmsg! _conn (format "NICK %s" n))
                                (recur (read-line) n))
               (= code "004") (add-pinger
                               (add-listener
                                (add-out-queue
                                 (add-in-queue
                                  (merge _conn {:created (now)
                                                :nick _nick})))))
               (re-find #"[45].." code) (throw
                                         (Exception.
                                          (format "%s: cannot connect to server" code)))
               :else (recur (read-line) _nick)))
            (recur (read-line) _nick)))))))

(defn watch [conns]
  (log "watcher: start")
  (send-off
   (agent conns)
   (fn resend [_conns]
     (let [logmsg (fn [statuses]
                    (format "watcher: %s"
                            (if (< 0 (count @_conns))
                              (s-util/str-join ", " statuses)
                              "no connections")))
           dead (filter dead? @_conns)
           want-reconnect (filter reconnect? @_conns)]
       (when @*watch*
         (log (logmsg (connection-statuses)))
         (doseq [c (concat dead want-reconnect)]
           (quit c)
           (let [{:keys [host port nick]} c
                 newc (log-in host port nick)]
             (log (format "watcher: reconnecting %s@%s as %s" nick host newc))))
         (Thread/sleep (* 1000 *watcher-interval*))
         (send-off *agent* resend)))
     conns)))

(defn make-privmsg [id]
  (fn [chan msg]
    (sendmsg (connection id) (format "PRIVMSG %s :%s" chan msg))))

(defn make-join [id]
  (fn [chan]
    (sendmsg (connection id) (format "JOIN %s" chan))))

(defn make-part [id]
  (fn [chan]
    (sendmsg (connection id) (format "PART %s" chan))))

(defn make-whois [id]
  (fn [nick]
    (sendmsg (connection id) (format "WHOIS %s" nick))))

(defn do-JOIN [conn chan]
  (sendmsg (connection conn) (format "JOIN %s" chan)))

(defn log-in [host port nick]
  (let [conn (connect {:host host :port port :nick nick})]
    (register-connection conn)
    (doseq [ch *channels*]
      (do-JOIN conn ch))
    (connection-id conn)))

(comment
  (watch *connections*)
  (def conn1 (log-in "irc.freenode.net" 6667 "drewr1"))
  (def conn2 (log-in "irc.freenode.net" 6667 "drewr2"))
  (uptime conn1)
  (uptime conn2)
  (quit conn1)
  (quit conn2)
  (quit-all)
  (System/exit 0)
)
