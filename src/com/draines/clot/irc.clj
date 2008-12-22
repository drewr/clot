(ns com.draines.clot.irc
  (:require [clojure.contrib.str-utils :as s-util])
  (:import [java.util Date UUID]
           [java.util.regex Pattern]
           [java.text SimpleDateFormat]
           [java.net Socket SocketException]
           [java.io InputStreamReader BufferedReader OutputStreamWriter BufferedWriter FileWriter]
           [java.util.concurrent LinkedBlockingQueue]))

(def *logfile* "/tmp/clot.log")
(def *channels* ["##test1"])
(def *keepalive-frequency* 45)
(def *use-console* false)
(def *max-retries* 10)
(def *watcher-interval* 10)
(def *send-delay* 1)
(def *watch* (atom true))
(defonce *connections* (atom []))

(def connect)

(defn append-file [filename s]
  (with-open [w (FileWriter. filename true)
              timestamp (.format (SimpleDateFormat. "yyyy-MM-dd HH:mm:ss.SSS")
                                 (java.util.Date.))]
    (.write w (format "%s %s" timestamp s))))

(defn connection-id [conn]
  (.toUpperCase (str (:id conn))))

(defn connection-id-short [conn]
  (re-find #"^.{4}" (connection-id conn)))

(defn connection-name [conn]
  (format "%s@%s/%s" (:nick conn) (:host conn) (connection-id-short conn)))

(defn connection [id]
  (let [id (.toUpperCase (str id))
        pat (Pattern/compile (format "^%s" id))
        matches (fn [conn]
                  (when (re-find (.matcher pat (connection-id conn)))
                    conn))]
    (some matches @*connections*)))

(defn same-connection? [c1 c2]
  (= (connection-id c1) (connection-id c2)))

(defn connection-established? [conn]
  (contains? conn :created))

(defn append-stdout [s]
  (print s)
  (.flush *out*))

(defn log
  ([s]
     (let [_s (format "*** %s\n" s)]
       (append-file *logfile* _s)
       (when *use-console*
         (append-stdout _s))))
  ([conn s]
     (let [id (connection-id-short conn)]
       (log (format "[%s] %s" id s)))))

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

(defn inc-retries! [conn]
  (atom-inc! (:retries conn)))

(defn reset-retries! [conn]
  (atom-set! (:retries conn) 0))

(defn register-connection [conn]
  (swap! *connections* conj conn))

(defn unregister-connection [conn]
  (swap! *connections*
         (fn [xs]
           (filter #(not (same-connection? conn %)) xs))))

(defn alive? [conn]
  (when (:sock conn)
    (and
     (not (.isClosed (:sock conn)))
     (not (.isInputShutdown (:sock conn))))))

(defn quit [conn & do-not-reconnect]
  (let [_conn (if (:id conn) conn (connection conn))]
    (when (alive? _conn)
      (log _conn (format "shutting down: %s" (connection-name _conn)))
      (stop-incoming-queue _conn)
      (stop-outgoing-queue _conn)
      (.close (:sock _conn))
      (unregister-connection _conn))))

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
  (reset-retries! conn))

(defn parse-msg! [conn msg]
  (cond
   (re-find #" PONG " msg) (do-PONG conn)))

(defn dispatch [conn line]
  (log conn line)
  (parse-msg! conn line))

(defn ping [conn]
  (sendmsg! conn (format "PING %d" (int (/ (System/currentTimeMillis) 1000))))
  (inc-retries! conn))

(defn reconnectable? [conn]
;  (and @(:reconnect conn)
;       (< 0 @(:remain conn)))
  true)

(defn make-queue [conn _dispatch & sleep]
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
  (let [f (fn [c]
            (loop []
              (when (alive? c)
                (Thread/sleep (* 1000 *keepalive-frequency*))
                (ping c)
                (recur))))]
    (log conn "starting keep-alive")
    (send-off (agent conn) f)))

(defn listen [conn]
  (let [f (fn [_conn]
            (loop []
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
                        (recur))
                      line))))))]
    (log conn (format "listening to %s:%d" (:host conn) (:port conn)))
    (send-off (agent conn) f)))

(defn connect [conn]
  (let [{:keys [host port nick]} conn
        sock (Socket. host port)
        _conn (merge conn
                     {:id (UUID/randomUUID)
                      :sock sock
                      :reader (get-reader sock)
                      :writer (get-writer sock)
                      :retries (atom 0)})
        _conn (merge _conn {:inq (make-queue _conn dispatch)
                            :outq (make-queue _conn sendmsg! :sleep)})]
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
               (= code "004") (let [__conn (merge _conn {:created (now)
                                                         :nick _nick
                                                         :pinger (keep-alive _conn)})
                                    conn-connected (merge __conn {:listener (listen __conn)})]
                                (log conn-connected (format "queue agent-errors: %s, %s"
                                                            (agent-errors (:inq conn-connected))
                                                            (agent-errors (:outq conn-connected))))
                                conn-connected)
               (re-find #"[45].." code) (throw
                                         (Exception.
                                          (format "%s: cannot connect to server" code)))
               :else (recur (read-line) _nick)))
            (recur (read-line) _nick)))))))

(defn watch [conns]
  (send-off
   (agent conns)
   (fn [_conns]
     (log "watcher: start")
     (let [status (fn [c]
                      (format "%s: %s"
                              (connection-id-short c)
                              (if (alive? c) (format "UP %d" (uptime c)) "DOWN")))]
       (loop []
         (when @*watch*
           (log (format "watcher: %s"
                        (if (< 0 (count @_conns))
                          (s-util/str-join ", " (map status @_conns))
                          "no connections")))
           (Thread/sleep (* 1000 *watcher-interval*))
           (recur))))
     (log "watcher: stop")
     (format "stopped %s" (now)))))

(defn log-in [host port nick]
  (let [conn (connect {:host host :port port :nick nick})]
    (register-connection conn)
    (connection-id conn)))

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

(comment
  (do
    (def *id* (log-in "irc.freenode.net" 6667 "drewr"))
    (def privmsg (make-privmsg *id*))
    (def join (make-join *id*))
    (def part (make-part *id*))
    (def whois (make-whois *id*))
    (doseq [ch *channels*] (join ch)))

  (do
    (def *id2* (log-in "irc.freenode.net" 6667 "drewrtest"))
    (def privmsg2 (make-privmsg *id2*))
    (def join2 (make-join *id2*))
    (def part2 (make-part *id2*))
    (def whois2 (make-whois *id2*))
    (doseq [ch *channels*] (join2 ch)))

  (def *watcher* (watch *connections*))

  (uptime *id*)
  (quit *id*)
  (quit-all)
  (System/exit 0)
)
