(ns conduit.irc
  (:use [conduit.core])
  (:import (java.util.concurrent LinkedBlockingQueue)
           (org.jibble.pircbot PircBot)
           (java.io Closeable)
           (clojure.lang IDeref)))

(defn wall-hack-method [class-name name- params obj & args]
  (-> class-name (.getDeclaredMethod (name name-) (into-array Class params))
      (doto (.setAccessible true))
      (.invoke obj (into-array Object args))))

(def ^{:dynamic true} *pircbot* nil)

;; TODO: need a similar reconnect utility function

(defn send-notice [target notice-str]
  (.sendNotice *pircbot* target notice-str))

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
                     :notice (partial send-notice target)
                     :action #(.sendAction this target %)}
          [fun value] (if (vector? input)
                        [(responses (first input)) (second input)]
                        [(:message responses) input])]
      (doseq [nv (.split (with-out-str (print value)) "\n")]
        (fun nv)))))

(defn pircbot [server nick]
  (let [mq (LinkedBlockingQueue.)
        connector (if (coll? server)
                    (fn [conn]
                      (let [[server port pass] server]
                        (.connect conn server port pass)))
                    #(.connect % server))
        server (if (coll? server) (first server) server)
        conn (proxy [PircBot IDeref Closeable] []
               (onConnect []
                 (.put mq [nick
                           [[:connect {:server server :nick nick :bot this}]
                            (constantly nil)]]))
               (onDisconnect []
                 (.put mq [nick
                           [[:disconnect {:server server :nick nick :bot this}]
                            (constantly nil)]]))
               (onMessage [channel sender login hostname message]
                 (.put mq [nick
                           [[:message {:channel channel
                                       :sender sender
                                       :login login
                                       :hostname hostname
                                       :message message
                                       :bot this
                                       :server server}]
                            (reply-selector this channel)]]))
               (onAction [sender login hostname target action]
                 (.put mq [nick
                           [[:action {:target target
                                      :sender sender
                                      :login login
                                      :hostname hostname
                                      :action action
                                      :bot this
                                      :server server}]
                            (reply-selector this target)]]))
               (onInvite [target-nick source-nick source-login source-hostname
                          channel]
                 (.put mq [nick
                           [[:invite {:target-nick target-nick
                                      :source-nick source-nick
                                      :source-login source-login
                                      :source-hostname source-hostname
                                      :channel channel
                                      :bot this
                                      :server server}]
                            (reply-selector this channel)]]))
               (onPrivateMessage [sender login hostname message]
                 (.put mq [nick
                           [[:private-message {:sender sender
                                               :login login
                                               :hostname hostname
                                               :message message
                                               :bot this
                                               :server server}]
                            (reply-selector this sender)]]))
               (onJoin [channel sender login hostname]
                 (.put mq [nick
                           [[:join {:sender sender
                                    :login login
                                    :hostname hostname
                                    :channel channel
                                    :bot this
                                    :server server}]
                            (reply-selector this channel)]]))
               (onPart [channel sender login hostname]
                 (.put mq [nick
                           [[:part {:sender sender
                                    :login login
                                    :hostname hostname
                                    :channel channel
                                    :bot this
                                    :server server}]
                            (reply-selector this channel)]]))
               (onQuit [nick login hostname reason]
                 (.put mq [nick
                           [[:quit {:nick nick
                                    :login login
                                    :hostname hostname
                                    :reason reason
                                    :bot this
                                    :server server}]
                            (constantly nil)]]))
               (onVersion [nick login hostname target]
                 (.put mq [nick
                           [[:version {:nick nick
                                       :login login
                                       :hostname hostname
                                       :target target
                                       :bot this
                                       :server server}]
                            (reply-selector this nick)]]))
               (deref []
                 mq)
               (close []
                 (.disconnect this)))]
    (wall-hack-method
     org.jibble.pircbot.PircBot :setName [String] conn nick)
    (connector conn)
    conn))

(defn a-irc [nick proc]
  (let [id nick]
    (assoc proc
      :type :irc
      :parts (assoc (:parts proc)
               id {:type :irc
                   id (reply-fn (:reply proc))}))))

(defn join [channels]
  (doseq [channel channels]
    (.joinChannel *pircbot* channel)))

(defn irc-run
  "start a single thread executing a proc"
  [proc & [channel-or-exception-handler & channels]]
  (let [funs (get-in proc [:parts (.getNick *pircbot*)])]
    (join
     (if (fn? channel-or-exception-handler)
       channels
       (conj channels channel-or-exception-handler)))
    (letfn [(next-msg [Q]
              (fn next-msg-inner [_]
                [[(.take Q)] next-msg-inner]))
            (handle-msg [fun msg]
              (try
                (let [[_ new-fn] (fun msg)]
                  [[] (partial handle-msg new-fn)])
                (catch Exception e
                  (if (fn? channel-or-exception-handler)
                    (channel-or-exception-handler e)
                    (.printStackTrace e))
                  [[] fun])))
            (run []
              (->> [(next-msg @*pircbot*)
                    (partial handle-msg (partial select-fn funs))]
                   (reduce comp-fn)
                   (a-run)
                   (dorun)))]
      (run))))

(comment

  (with-open [p (pircbot "irc.freenode.net" "conduitbot")]
    (binding [*pircbot* p]
      (irc-run
       (a-irc "conduitbot"
              (a-arr (constantly "hello")))
       "#clojurebot")))

  )
