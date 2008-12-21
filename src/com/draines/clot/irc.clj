(ns com.draines.clot.irc
  (:import [java.util Date]
           [java.text SimpleDateFormat]
           [java.net Socket SocketException]
           [java.io InputStreamReader BufferedReader OutputStreamWriter BufferedWriter FileWriter]
           [java.util.concurrent LinkedBlockingQueue]))

(def *logfile* "/tmp/clot.log")
(def *channels* ["##test1"])
(def *keepalive-frequency* 45)
(def *use-console* false)
(def *connection-attempts* 5)
(def *retry-delay* 3)
(def *send-delay* 1)
(defonce *connections* (atom {}))

(def connect)

(defn append-file [filename s]
  (with-open [w (FileWriter. filename true)
              timestamp (.format (SimpleDateFormat. "yyyy-MM-dd HH:mm:ss.SSS")
                                 (java.util.Date.))]
    (.write w (format "%s %s" timestamp s))))

(defn append-stdout [s]
  (print s)
  (.flush *out*))

(defn log [s]
  (let [_s (format "*** %s\n" s)]
    (append-file *logfile* _s)
    (when *use-console*
      (append-stdout _s))))

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

(defn connection [id]
  (@*connections* id))

(defn connection-id [conn]
  (format "%s@%s/%d" (:nick conn) (:host conn) (.getTime (:created conn))))

(defn register-connection [conn]
  (swap! *connections* conj {(connection-id conn) conn}))

(defn unregister-connection [conn]
  (swap! *connections* dissoc (connection-id conn)))

(defn alive? [conn]
  (when (:sock conn)
    (and
     (not (.isClosed (:sock conn)))
     (not (.isInputShutdown (:sock conn))))))

(defn quit [conn & do-not-reconnect]
  (when (alive? conn)
    (log "shutting down")
    (stop-incoming-queue conn)
    (stop-outgoing-queue conn)
    (.close (:sock conn))
    (when do-not-reconnect
      (atom-set! (:reconnect conn) false))
    (unregister-connection conn)))

(defn quit-all []
  (loop []
    (when (< 0 (count @*connections*))
      (quit (second (first @*connections*)) true))))

(defn get-reader [sock]
  (BufferedReader. (InputStreamReader. (.getInputStream sock))))

(defn get-writer [sock]
  (BufferedWriter. (OutputStreamWriter. (.getOutputStream sock))))

(defn sendmsg! [conn line]
  (.write (:writer conn) (format "%s\r\n" line))
  (.flush (:writer conn))
  (log (format "-> %s" line)))

(defn dispatch [conn line]
  (log line))

(defn ping [conn]
  (sendmsg! conn (format "PING %d" (int (/ (System/currentTimeMillis) 1000)))))

(defn init-attempts []
  (atom *connection-attempts*))

(defn atom-dec! [a]
  (swap! a dec))

(defn reconnectable? [conn]
  (and @(:reconnect conn)
       (< 0 @(:remain conn))))

(defn make-queue [conn _dispatch & sleep]
  (let [a (agent {:q (LinkedBlockingQueue.)})
        f (fn [queue]
            (log (format "make-queue: started %s" _dispatch))
            (loop []
              (let [el (.take (:q queue))]
                (when-not (= "STOP" (.toUpperCase (str el)))
                  (_dispatch conn el)
                  (when sleep
                    (Thread/sleep (* 1000 *send-delay*)))
                  (recur))))
            (log (format "make-queue: stopped %s" _dispatch))
            :stopped)]
    (log (format "make-queue: %s -> %s" _dispatch a))
    (send-off a f)))

(defn keep-alive [conn]
  (let [f (fn [c]
            (loop []
              (when (alive? c)
                (Thread/sleep (* 1000 *keepalive-frequency*))
                (ping c)
                (recur))))]
    (log "starting keep-alive")
    (send-off (agent conn) f)))

(defn listen [conn]
  (let [f (fn [_conn]
            (log (format "listen: %s" _conn))
            (loop [__conn _conn]
              (let [is-connected (atom true)
                    connected (fn [c]
                                c)
                    reconnecting (fn [c]
                                   (atom-dec! (:remain c))
                                   (log (format "reconnecting: %s" c))
                                   (connect c))]
                (binding [*in* (:reader __conn)]
                  (let [line (try
                              (read-line)
                              (catch SocketException e
                                (toggle is-connected)
                                (log (format "toggle is-connected to %s" is-connected))
                                (log "quitting __conn")
                                (quit __conn)
                                (log (format "__conn reconnectable? %s" (reconnectable? __conn)))
                                __conn))]
                    (if (and @is-connected line)
                      (do
                        (add-incoming-message __conn line)
                        (recur (connected __conn)))
                      (when (reconnectable? __conn)
                        (log (format "sleeping for %d seconds" *retry-delay*))
                        (Thread/sleep (* 1000 *retry-delay*))
                        (log (format "reconnecting (%d times left)" @(:remain __conn)))
                        (recur (reconnecting __conn)))))))))]
    (log (format "listening to %s:%d" (:host conn) (:port conn)))
    (send-off (agent conn) f)))

(defn connect [conn]
  (let [{:keys [host port nick]} conn
        sock (Socket. host port)
        _conn (merge conn
                     {:sock sock
                      :reader (get-reader sock)
                      :writer (get-writer sock)})
        foo (log (format "connect (foo): %s" _conn))
        _conn (merge _conn {:inq (make-queue _conn dispatch)
                            :outq (make-queue _conn sendmsg! :sleep)})
        bar (log (format "connect (bar): %s" _conn))]
    (log (format "connect: %s" _conn))
    ;; (log (format "connecting to %s:%d" host port))
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
               (= code "004") (let [__conn (merge _conn {:pinger (keep-alive _conn)
                                                         :nick _nick
                                                         :created (now)
                                                         :reconnect (atom true)
                                                         :remain (init-attempts)})
                                    conn-connected (merge __conn {:listener (listen __conn)})]
                                (log (format "connect: %s" (sort (keys conn-connected))))
                                (register-connection conn-connected)
                                conn-connected)
               (re-find #"[45].." code) (throw
                                         (Exception.
                                          (format "%s: cannot connect to server" code)))
               :else (recur (read-line) _nick)))
            (recur (read-line) _nick)))))))

(defn make-privmsg [conn]
  (fn [chan msg]
    (sendmsg conn (format "PRIVMSG %s :%s" chan msg))))

(defn make-join [conn]
  (fn [chan]
    (sendmsg conn (format "JOIN %s" chan))))

(defn make-part [conn]
  (fn [chan]
    (sendmsg conn (format "PART %s" chan))))

(defn make-whois [conn]
  (fn [nick]
    (sendmsg conn (format "WHOIS %s" nick))))

(comment
  (do
    (def *conn* (connect {:host "irc.freenode.net" :port 6667 :nick "drewr"}))
    (def privmsg (make-privmsg *conn*))
    (def join (make-join *conn*))
    (def part (make-part *conn*))
    (def whois (make-whois *conn*))
    (doseq [ch *channels*] (join ch)))

  (uptime *conn*)
  (quit *conn*)
  (quit-all)
  (System/exit 0)
)
