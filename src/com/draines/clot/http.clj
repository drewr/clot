(ns com.draines.clot.http
  (:import [org.apache.commons.httpclient HttpClient]
           [org.apache.commons.httpclient.methods GetMethod PostMethod]))


(defn httpget [url]
  (let [method (GetMethod. url)
        client (doto (HttpClient.) (.executeMethod method))]
    (str
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

