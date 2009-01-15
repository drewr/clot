(ns com.draines.clot.handlers.tumblr
  (:require [com.draines.clot.irc :as clot]
            [clojure.contrib.str-utils :as str-utils])
  (:use [com.draines.clot.http :only [httppost page-title]]
        [clojure.contrib.test-is :only [is deftest run-tests]]))

(def types
     [[:image {:re #"(?i)^\s*(?:(.*?)\s+)?(http:\S+\.(?:jpe?g|png|gif))(?:\s+(\S.*))?$"
               :poster :caption}]
      [:video {:re #"(?i)^\s*(?:(.*?)\s+)?(http://(?:www\.)?youtube\.com/\S+\?\S+)(?:\s+(.*))?$"
               :poster :caption}]
      [:link {:re #"(?i)^\s*(?:(.*?)\s+)?(https?://\S+)\s*(?:\s+(\S.*))?$"
              :poster :description}]
      [:quote {:re #"(?i)^\s*\"([^\"]+)\"\s+--\s*(.*?)(?:\s+\((https?:.*)\))?$"
               :poster :source}]])

(defn type-attrs [type]
  (loop [[[t attrs] & pairs] types]
    (if (= type t)
      attrs
      (when pairs
        (recur pairs)))))

(defn poster-field [type]
  (:poster (type-attrs type)))

(defn config []
  (let [properties (doto (java.util.Properties.)
                     (.load (java.io.StringReader.
                             (slurp (format "%s/.authtumblr"
                                            (System/getProperty "user.home"))))))]
    (reduce
     (fn [acc [k v]]
       (conj acc {(keyword k) v})) {}
     (into {} properties))))

(defn write-tumblr [data]
  (let [{:keys [email password api]} (config)]
    (httppost api
              (merge {:email email
                      :password password} data)
              nil)))

(defn tumblr-post-url [id]
  (let [host (:host (config))]
    (format "%s/post/%s" host id)))

(defmulti make-params :type)

(defmethod make-params :image [{:keys [matches]}]
  (throw (Exception. "don't know how to post an image yet")))

(defmethod make-params :video [{:keys [matches]}]
  (throw (Exception. "don't know how to post a video yet")))

(defmethod make-params :link [{:keys [matches]}]
  (let [[before link after] matches]
    {:type :link
     :name (or before after (page-title link))
     :url link
     :description ""}))

(defmethod make-params :quote [{:keys [matches]}]
  (let [[quote source link] matches
        sourcehtml (when link
                     (format "<a href=\"%s\">%s</a>" link source))]
    {:type :quote
     :quote quote
     :source (or sourcehtml source)}))

(defn parse
  {:test (fn []
           (is (= :link (:type (parse "Foo bar baz http://foo.com"))))
           (is (= :image (:type (parse "http://foo.com/image.JPG"))))
           (is (= :video (:type (parse "http://www.youtube.com/watch?v=avch-fRFmbw"))))
           (is (= :quote (:type (parse "\"Foo!\" --me")))))}
  ([text]
     (loop [[[type attrs] & pairs] types]
       (if-let [[orig & matches] (re-find (:re attrs) text)]
         {:type type :matches matches}
         (when pairs
           (recur pairs))))))

(defn add-poster [nick params]
  (if-let [field (poster-field (:type params))]
    (merge params {field (format "%s (posted by %s)" (field params) nick)})
    params))

(defn ->PRIVMSG [conn raw nick user userhost chan message]
  (when-let [type (parse message)]
    (let [url (try
               (let [params (add-poster nick (make-params type))]
                 (tumblr-post-url (write-tumblr params)))
               (catch Exception e
                 e))
          msg (if (isa? (class url) java.lang.Exception)
                (format "%s: I didn't post because of a %s" nick url)
                (format "created link for %s at %s" nick url))]
      (clot/irc-privmsg conn chan msg))))


;(run-tests)
