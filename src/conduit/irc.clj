(ns conduit.irc
  (:use [conduit.core])
  (:import (java.util.concurrent ConcurrentHashMap LinkedBlockingQueue)
           (org.jibble.pircbot PircBot)))

(defn wall-hack-method [class-name name- params obj & args]
  (-> class-name (.getDeclaredMethod (name name-) (into-array Class params))
      (doto (.setAccessible true))
      (.invoke obj (into-array Object args))))

(defn- reply-fn [f]
  (partial (fn irc-reply-fn [f [value k]]
             (let [[[new-value] new-f] (f value)]
               (k (str new-value))
               [[] (partial irc-reply-fn new-f)]))
           f))

(defn a-irc
  [server nick proc]
  (let [id [server nick]
        reply-id (str id "-reply")]
    (assoc proc
      :type :irc
      :parts (assoc (:parts proc)
               id {:type :irc
                      id (reply-fn (:reply proc))
                      reply-id (reply-fn (:reply proc))}))))

(defn irc-run
  "start a single thread executing a proc"
  [proc server nick & channels]
  (let [mq (LinkedBlockingQueue.)
        conn (proxy [PircBot] []
               (onMessage [channel sender login hostname message]
                 (.put mq [[server nick]
                           [[:message channel sender login hostname message]
                            #(.sendMessage this channel %)]]))
               (onPrivateMessage [sender login hostname message]
                 (.put mq [[server nick]
                           [[:private-message sender login hostname message]
                            #(.sendMessage this sender %)]])))]
    (try
      (wall-hack-method
       org.jibble.pircbot.PircBot :setName [String] conn nick)
      (.connect conn server)
      (doseq [channel channels]
        (.joinChannel conn channel))
      (letfn [(next-msg [Q]
                (fn next-msg-inner [_]
                  [[(.take Q)] next-msg-inner]))
              (handle-msg [fun msg]
                (try
                  (let [[_ new-fn] (fun msg)]
                    [[] (partial handle-msg new-fn)])
                  (catch Exception e
                    (.printStackTrace e)
                    [[] fun])))]
        (->> [(next-msg mq)
              (partial handle-msg
                       (partial select-fn
                                (get-in proc [:parts [server nick]])))]
             (reduce comp-fn)
             (a-run)
             (dorun)))
      (finally
       (.disconnect conn)))))

(comment

  (irc-run
   (a-comp (a-irc
            "irc.freenode.net"
            "clojurebotIII"
            (a-arr (fn [x]
                     (println "FOO")
                     x)))
           pass-through)
   "irc.freenode.net"
   "clojurebotIII"
   "#clojurebot")


  )
