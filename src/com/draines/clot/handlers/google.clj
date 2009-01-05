(ns com.draines.clot.handlers.google
  (:require [com.draines.clot.irc :as clot]
            [clojure.contrib.str-utils :as str-utils]
            [clojure.xml :as xml])
  (:import [net.sf.json JSONObject]
           [org.apache.commons.httpclient HttpClient]
           [org.apache.commons.httpclient.methods GetMethod]))

;; http://code.google.com/apis/ajaxsearch/documentation/reference.html#_intro_fonje

(def *google* "http://ajax.googleapis.com/ajax/services/search/web?v=1.0&rsz=large&start=%s&q=%s")
(def *last-response* (ref []))
(def *results-per-page* 8)
(def *max-results* 24)

(defn httpget [url]
  (let [method (GetMethod. url)
        client (doto (HttpClient.) (.executeMethod method))]
    (String. (.getResponseBody method))))

(defn google [query page]
  (let [terms (str-utils/re-split #" " query)
        response (httpget (format *google* (* page *results-per-page*) (str-utils/str-join "+" terms)))
        json (JSONObject/fromObject response)
        urls (map #(.get % "url") (-> json (.get "responseData") (.get "results")))]
    (lazy-cat urls (google query (inc page)))))

(defn push-results [r xs]
  (dosync (ref-set *last-response* xs)))

(defn pop-result [r]
  (dosync
   (let [x (first @r)]
     (ref-set r (rest @r))
     x)))

(defn ->PRIVMSG [conn nick user userhost chan msg]
  (when-let [[orig query] (re-find #"^,g (.*)" msg)]
    (push-results *last-response* (take *max-results* (google query 0)))
    (clot/irc-privmsg conn chan (pop-result *last-response*)))
  (when-let [[orig] (re-find #"^,g$" msg)]
    (clot/irc-privmsg conn chan (pop-result *last-response*))))

