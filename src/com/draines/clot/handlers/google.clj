(ns com.draines.clot.handlers.google
  (:require [com.draines.clot.irc :as clot]
            [clojure.contrib.str-utils :as str-utils]
            [clojure.xml :as xml])
  (:use [com.draines.clot.http :only [httpget]])
  (:import [net.sf.json JSONObject]
           [org.apache.commons.httpclient HttpClient]
           [org.apache.commons.httpclient.methods GetMethod]))

;; http://code.google.com/apis/ajaxsearch/documentation/reference.html#_intro_fonje

(def *google* "http://ajax.googleapis.com/ajax/services/search/web?v=1.0&rsz=large&start=%s&q=%s")
(def *last-response* (ref []))
(def *results-per-page* 8)
(def *max-results* 24)

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

(defn respond [conn chan]
  (clot/irc-privmsg conn chan (format "!%s" (pop-result *last-response*))))

(defn ->PRIVMSG [conn raw nick user userhost chan message]
  (when-let [[orig query] (re-find #"^,g (.*)" message)]
    (push-results *last-response* (take *max-results* (google query 0)))
    (respond conn chan))
  (when-let [[orig] (re-find #"^,g$" message)]
    (respond conn chan)))

