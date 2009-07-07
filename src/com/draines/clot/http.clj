(ns com.draines.clot.http
  (:use [clojure.test :only [run-tests is]]
        [clojure.contrib.str-utils :only [re-gsub]])
  (:import [org.apache.commons.httpclient HttpClient]
           [org.apache.commons.httpclient.methods GetMethod PostMethod]))


(defn httpget [url]
  (let [method (GetMethod. url)
        client (doto (HttpClient.) (.executeMethod method))]
    (apply str
     (concat
      (line-seq (java.io.BufferedReader.
                 (java.io.InputStreamReader.
                  (.getResponseBodyAsStream method))))))))

(defn httppost [url params body]
  (let [method (PostMethod. url)
        client (HttpClient.)]
    (doseq [[k v] params]
      (when (and k v)
        (let [v (if (keyword? v)
                  (name v)
                  v)]
          (.addParameter method (name k) v))))
    (doto client (.executeMethod method))
    (String. (.getResponseBody method))))

(defn extract-tag
  {:test (fn []
           (is (= "Airplane Crash-lands in Hudson"
                  (extract-tag :title "<title>Airplane Crash-lands
                                                  in Hudson
                                            </title>"))))}
  ([tag html]
     (let [match (re-find
                  (java.util.regex.Pattern/compile
                   (format "(?i)<%s>([^<]+)</%s>" (name tag) (name tag)))
                  html)]
       (when match
         (.trim (re-gsub #"\s+" " " (second match)))))))

(defn url-title [url]
  (extract-tag :title (httpget url)))

;(run-tests)