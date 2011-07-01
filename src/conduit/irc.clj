(ns conduit.irc
  (:use [conduit.core])
  (:import (java.util.concurrent ConcurrentHashMap LinkedBlockingQueue)
           (org.jibble.pircbot PircBot)
           (java.io Closeable)
           (clojure.lang IDeref)))

(defonce ^{:private true} connection-cache (atom {}))

(defn wall-hack-method [class-name name- params obj & args]
  (-> class-name (.getDeclaredMethod (name name-) (into-array Class params))
      (doto (.setAccessible true))
      (.invoke obj (into-array Object args))))

(defn- reply-fn [f]
  (partial (fn irc-reply-fn [f [value k]]
             (let [[[new-value] new-f] (f value)]
               (when new-value
                 (k new-value))
               [[] (partial irc-reply-fn new-f)]))
           f))

(defn reply-selector [this target]
  (fn [input]
    (let [responses {:message #(.sendMessage this target %)
                     :notice #(.sendNotice this target %)}
          [fun value] (if (vector? input)
                        [(responses (first input)) (second input)]
                        [(:message responses) input])]
      (doseq [nv (.split (with-out-str (print value)) "\n")]
        (fun nv)))))

(defn pircbot [server nick]
  (locking #'pircbot
    (if-let [conn (get @connection-cache [server nick])]
      (let [[mq ref-count] @conn]
        (swap! ref-count inc)
        conn)
      (let [mq (LinkedBlockingQueue.)
            ref-count (atom 1)
            conn (proxy [PircBot IDeref Closeable] []
                   (onConnect []
                     (.put mq [[server nick]
                               [[:connect {:server server :nick nick :bot this}]
                                (constantly nil)]]))
                   (onDisconnect []
                     (.put mq [[server nick]
                               [[:disconnect {:server server :nick nick :bot this}]
                                (constantly nil)]]))
                   (onMessage [channel sender login hostname message]
                     (.put mq [[server nick]
                               [[:message {:channel channel
                                           :sender sender
                                           :login login
                                           :hostname hostname
                                           :message message
                                           :bot this}]
                                (reply-selector this channel)]]))
                   (onAction [sender login hostname target action]
                     (.put mq [[server nick]
                               [[:action {:target target
                                          :sender sender
                                          :login login
                                          :hostname hostname
                                          :action action
                                          :bot this}]
                                #(.sendMessage this target %)]]))
                   (onInvite [target-nick source-nick source-login source-hostname channel]
                     (.put mq [[server nick]
                               [[:invite {:target-nick target-nick
                                          :source-nick source-nick
                                          :source-login source-login
                                          :source-hostname source-hostname
                                          :channel channel
                                          :bot this}]
                                #(.sendMessage this channel %)]]))
                   (onPrivateMessage [sender login hostname message]
                     (.put mq [[server nick]
                               [[:private-message {:sender sender
                                                   :login login
                                                   :hostname hostname
                                                   :message message
                                                   :bot this}]
                                (reply-selector this sender)]]))
                   (onJoin [channel sender login hostname]
                     (.put mq [[server nick]
                               [[:join {:sender sender
                                        :login login
                                        :hostname hostname
                                        :channel channel
                                        :bot this}]
                                #(.sendMessage this channel %)]]))
                   (onPart [channel sender login hostname]
                     (.put mq [[server nick]
                               [[:part {:sender sender
                                        :login login
                                        :hostname hostname
                                        :channel channel
                                        :bot this}]
                                #(.sendMessage this channel %)]]))
                   (onQuit [nick login hostname reason]
                     (.put mq [[server nick]
                               [[:quit {:nick nick
                                        :login login
                                        :hostname hostname
                                        :reason reason
                                        :bot this}]
                                (constantly nil)]]))
                   (onVersion [nick login hostname target]
                     (.put mq [[server nick]
                               [[:version {:nick nick
                                           :login login
                                           :hostname hostname
                                           :target target
                                           :bot this}]
                                #(.sendNotice nick %)]]))
                   (deref [] [mq ref-count])
                   (close []
                     (when (zero? @ref-count)
                       (swap! connection-cache dissoc [server nick])
                       (.disconnect this))))]
        (swap! connection-cache assoc [server nick] conn)
        conn))))

(defn a-irc
  [server nick & [proc]]
  (if proc
    (let [id [server nick]]
      (assoc proc
        :type :irc
        :parts (assoc (:parts proc)
                 id {:type :irc
                     id (reply-fn (:reply proc))})))
    (conduit-proc
     (fn [[target input]]
       (with-open [bot (pircbot server nick)]
         ((reply-selector bot target) input))
       []))))

(defn irc-run
  "start a single thread executing a proc"
  [proc server nick & channels]
  (with-open [conn (pircbot server nick)]
    (let [[mq] @conn]
      (when-not (.isConnected conn)
        (locking conn
          (wall-hack-method
           org.jibble.pircbot.PircBot :setName [String] conn nick)
          (if (coll? server)
            (.connect conn
                      (first server)
                      (second server)
                      (second (rest server)))
            (.connect conn server))))
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
                    [[] fun])))
              (run []
                (->> [(next-msg mq)
                      (partial handle-msg
                               (partial select-fn
                                        (get-in proc [:parts [server nick]])))]
                     (reduce comp-fn)
                     (a-run)
                     (dorun)))]
        (run)))))

(comment

  (irc-run
   (a-comp (a-irc
            "irc.freenode.net"
            "conduitbot"
            (a-select
             'message (a-par :message
                             pass-through)))
           pass-through)
   "irc.freenode.net"
   "conduitbot"
   "#clojurebot")


  (conduit-map
   (a-irc "irc.freenode.net"
          "conduitbot")
   [["#clojurebot" "hello?"]])


  )
