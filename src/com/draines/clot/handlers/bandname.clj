(ns com.draines.clot.handlers.bandname
  (:use [com.draines.clot.irc :only [irc-privmsg]]
        [com.draines.clot.http :only [httpget url-title]]
        [clojure.test :only [is deftest run-tests]]))

(def max-tries 3)
(def url-random "http://en.wikipedia.org/wiki/Special:Random")

(defn get-page []
  (httpget url-random))

(defn extract-bandname
  {:test (fn []
           (is (= "Up North" (extract-bandname "Up North (book) - Wikipedia...")))
           (is (= "Atmospheric duct" (extract-bandname "Atmospheric duct - Wikipedia..."))))}
  ([title]
     (when-let [m (re-find #"(.+?)(?:\(.+\))? - Wikipedia.*" title)]
       (.trim (str (second m))))))

(defn bandname []
  (loop [c 0]
    (let [title (url-title url-random)
          bn (extract-bandname title)]
      (if bn
        bn
        (when (< c max-tries)
          (recur (inc c)))))))

(defn ->PRIVMSG [conn raw nick user userhost chan message]
  (when (re-find #"^,bn$" message)
    (irc-privmsg conn chan "foo!")))

(comment
  (run-tests)

)
