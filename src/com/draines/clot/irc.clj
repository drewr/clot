(ns com.draines.clot.irc
  (:require [clojure.contrib.str-utils :as s-util])
  (:import [java.util Date UUID]
           [java.util.regex Pattern]
           [java.text SimpleDateFormat]
           [java.net Socket SocketException UnknownHostException]
           [java.io InputStreamReader BufferedReader OutputStreamWriter BufferedWriter FileWriter]
           [java.util.concurrent LinkedBlockingQueue]))

(def *logfile* "/tmp/clot.log")
(def *logger* (agent *logfile*))
(def *stdout* (agent nil))
(def *channels* ["##clot-test"])
(def *keepalive-frequency* 45)
(def *use-console* false)
(def *max-failed-pings* 2)
(def *watcher-interval* 3)
(def *send-delay* 1)
(def *watch?* (atom true))
(defonce *next-id* (atom 1))
(defonce *connections* (ref []))

(declare log-in)
(declare connect)
(declare alive?)
(declare quit)
(declare make-socket)
(declare reconnect)
(declare *watcher*)

(def *irc-verbs*
     {:JOIN    #"^:([^!]+)!.=([^@]+)@([^ ]+) JOIN ([^ ]+)"
      :PRIVMSG #"^:([^!]+)!.=([^@]+)@([^ ]+) PRIVMSG ([^ ]+) :(.*)"
      :PONG    #"^:([^ ]+) PONG ([^ ]+) :(.*)"})

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
  (dosync
   (commute *connections* conj conn)))

(defn unregister-connection [conn]
  (dosync
   (commute *connections*
            (fn [xs]
              (filter #(not (same-connection? conn %)) xs)))))

(defn connection-agent-errors [conn]
  (reduce #(conj %1 %2) {}
          (filter identity (for [[k v] conn]
                             (when (instance? clojure.lang.Agent v)
                               {k (agent-errors v)})))))

(defn connection-agent-errors? [conn]
  (filter identity (vals (connection-agent-errors conn))))

(defn network? [& [conn]]
  (let [sock (make-socket (:host conn)
                          (:port conn))]
    (when sock
      (do
        (.close sock)
        true))))

(defn errors? [conn]
  (connection-agent-errors? conn))

(defn no-errors? [conn]
  (not (errors? conn)))

(defn closed-socket? [conn]
  (when (:sock conn)
    (.isClosed (:sock conn))))

(defn closed-streams? [conn]
  (when (:sock conn)
    (or (.isOutputShutdown (:sock conn))
        (.isInputShutdown (:sock conn)))))

(defn ping-count-exceeded? [conn]
  (when (:pings conn)
    (>= @(:pings conn) *max-failed-pings*)))

(defn alive? [conn]
  (and
   (not (errors? conn))
   (not (closed-socket? conn))
   (not (closed-streams? conn))
   (not (ping-count-exceeded? conn))))

(defn dead? [conn]
  (let [res (not (alive? conn))]
    (log conn (format "dead: %s" res))
    (reconnect conn)
    res))

(defn quit? [conn]
  @(:quit? conn))

(defn reconnect? [conn]
  (let [res (when-not (quit? conn)
              (or
               (errors? conn)
               @(:reconnect? conn)))]
    ;; (log conn (format "reconnect: %s" res))
    res))

(defn reconnect [conn]
  (atom-set! (:quit? conn) false)
  (atom-set! (:reconnect? conn) true))

(defn reconnect! [conn]
  (let [{:keys [host port nick]} conn]
    (log-in host port nick)))

(defn quit [conn & do-not-reconnect]
  (let [_conn (connection conn)]
    (log _conn (format "shutting down: %s" (connection-name _conn)))
    (stop-incoming-queue _conn)
    (stop-outgoing-queue _conn)
    (.close (:sock _conn))
    (atom-set! (:quit? _conn) true)))

(defn quit-all []
  (dosync
   (doseq [conn @*connections*]
     (quit conn)
     (unregister-connection conn))))

(defn get-reader [sock]
  (BufferedReader. (InputStreamReader. (.getInputStream sock))))

(defn get-writer [sock]
  (BufferedWriter. (OutputStreamWriter. (.getOutputStream sock))))

(defn sendmsg! [conn line]
  (log conn (format "-> %s" line))
  (try
   (.write (:writer conn) (format "%s\r\n" line))
   (.flush (:writer conn))
   (catch SocketException e
     (log conn "sendmsg!: can't write, closing socket")
     (.close (:sock conn)))))

(defn ->PONG [conn args]
  (reset-pings! conn)
  (log conn (format "PONG %s" args)))

(defn ->PRIVMSG [conn args]
  (let [[nick user userhost chan msg] args]
    (log conn (format "PRIVMSG %s <%s> %s" chan nick msg))))

(defn ->JOIN [conn args]
    (log conn (format "JOIN %s" args)))

(defn msg-tokens [msg]
  (loop [pairs *irc-verbs*]
    (let [[verb pattern] (first pairs)]
      (when verb
        (let [tokens (re-find pattern msg)]
          (if tokens
            [verb (rest tokens)]
            (recur (rest pairs))))))))

(defn dispatch [conn line]
  (if-let [tokens (msg-tokens line)]
    (let [[verb args] tokens
          fname (second (re-find #"^:(.*)" (str verb)))
          f (find-var (symbol (format "%s/->%s" (str (:ns (meta #'dispatch))) fname)))]
      (when f
        (send-off (agent conn) f args)))
    (log conn line)))

(defn ping [conn]
  (sendmsg! conn (format "PING %d" (int (/ (System/currentTimeMillis) 1000))))
  (inc-pings! conn))

(defn connection-statuses [conns]
  (map #(format "%s: %s"
                (connection-id %)
                (cond
                 (alive? %) (format "UP %d" (uptime %))
                 (quit? %) "QUIT"
                 :else "DOWN")) conns))

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
            (try
             (when (alive? c)
               (Thread/sleep (* 1000 *keepalive-frequency*))
               (ping c)
               (send-off *agent* resend)
               c)
             (catch Exception e
               (log c (with-out-str
                        (.printStackTrace e)))
               :error)))]
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

(defn make-socket [host port]
  (try
   (let [sock (Socket. host port)
         localport (.getLocalPort sock)]
     (log (format "make-socket: connected to %s:%d on %d" host port localport))
     sock)
   (catch UnknownHostException e
     (log (format "make-socket: host not found: %s" host))
     nil)
   (catch SocketException e
     (log "make-socket: can't create socket")
     nil)
   (catch Exception e
     (log "make-socket: other fail")
     nil)))

(defn connect [info]
  (let [{:keys [host port nick]} info
        sock (make-socket host port)]
    (when sock
      (let [_conn (merge (sorted-map :id (atom-inc! *next-id*)
                                     :uuid (UUID/randomUUID)
                                     :sock sock
                                     :reader (get-reader sock)
                                     :writer (get-writer sock)
                                     :pings (atom 0) ; unanswered
                                     :reconnect? (atom false)
                                     :quit? (atom false)
                                     :pinger nil
                                     :inq nil
                                     :outq nil
                                     :listener nil
                                     :created nil) info)
            add-in-queue (fn [m] (merge m {:inq (make-queue m dispatch)}))
            add-out-queue (fn [m] (merge m {:outq (make-queue m sendmsg! :sleep)}))
            add-pinger (fn [m] (merge m {:pinger (keep-alive m)}))
            add-listener (fn [m] (merge m {:listener (listen m)}))]
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
                (recur (read-line) _nick)))))))))

(defn watch [conns]
  (let [statusmsg (fn [statuses]
                    (format "watcher: %s"
                            (if statuses
                              (s-util/str-join ", " statuses)
                              "no connections")))]
    (log "watcher: start")
    (send-off
     (agent conns)
     (fn resend [_conns]
       (if @*watch?*
         (let [to-reconnect (dosync (filter reconnect? @_conns))
               waiting? (< 0 (count to-reconnect))]
           (if-not waiting?
             (do
               ;; (log (statusmsg (connection-statuses @_conns)))
               (Thread/sleep (* 1000 *watcher-interval*)))
             (doseq [c to-reconnect]
               (let [{:keys [host port nick]} c]
                 (log (format "watcher: reconnecting %s@%s" nick host)))
               (when (network? c)
                 (do
                   (quit c)
                   (reconnect! c)))))
           (send-off *agent* resend))
         (log "watcher: stop"))
       _conns))))

(defn stop-watcher! []
  (atom-set! *watch?* false))

(defn start-watcher! []
  (send-off
   (agent nil)
   (fn [x]
     (stop-watcher!)
     (Thread/sleep (+ 500 (* 1000 *watcher-interval*)))
     (atom-set! *watch?* true)
     (def *watcher* (watch *connections*)))))

(defn do-PRIVMSG [conn chan msg]
  (sendmsg (connection conn) (format "PRIVMSG %s :%s" chan msg)))

(defn do-PART [conn chan]
  (sendmsg (connection conn) (format "PART %s" chan)))

(defn do-WHOIS [conn nick]
  (sendmsg (connection conn) (format "WHOIS %s" nick)))

(defn do-JOIN [conn chan]
  (sendmsg (connection conn) (format "JOIN %s" chan)))

(defn do-IDENTIFY [conn password]
  (do-PRIVMSG conn "nickserv" (format "identify %s" password)))

(defn log-in [host port nick & [password]]
  (let [conn (connect {:host host :port port :nick nick})]
    (log conn (format "log-in: logging in to %s" host port))
    (when conn
      (register-connection conn)
      (when password
        (do-IDENTIFY conn password))
      (doseq [ch *channels*]
        (do-JOIN conn ch))
      (connection-id conn))))

(start-watcher!)

(comment
  (def conn1 (log-in "irc.freenode.net" 6667 "drewr1"))
  (def conn2 (log-in "irc.freenode.net" 6667 "drewr2"))
  (uptime conn1)
  (uptime conn2)
  (quit conn1)
  (quit conn2)
  (quit-all)
  (System/exit 0)

)