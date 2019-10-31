(ns open-beat-control.osc-server
  "Manages the OSC server used to offer Beat Link services."
  (:require [overtone.osc :as osc]
            [open-beat-control.util :as util :refer [device-finder virtual-cdj beat-finder]]
            [taoensso.timbre :as timbre]))

(defonce
  ^{:doc "The OSC server"}
  server
  (atom nil))

(defonce
  ^{:private true
    :doc "Keeps track of the peer with which to send responses to messages
  coming from particular clients. If none has been specified, we
  create a default one which will respond to a port one higher than
  the one on which we are listening, but clients can override this
  using the /respond command.

  Keys in the map are a tuple of the client's host and port, values
  are the OSC peer that can be used to send responses to that client."}
  response-map
  (atom {}))

(defn response-client
  "Returns the OSC client that should be used to respond to an incoming
  OSC message, creating it if necessary."
  [msg]
  (let [{:keys [src-host src-port]} msg
        updated                     (swap! response-map update [src-host src-port]
                                           #(or % (osc/osc-client src-host (inc src-port))))]
    (get updated [src-host src-port])))

(defn- response-port
  "Determines the port on which a response to the message will be sent,
  by peeking inside the osc-clj client structure."
  [msg]
  @(:port (response-client msg)))

(defn respond
  "Sends an OSC message to the appropriate port in order to respond to
  an incoming message."
  [incoming path & args]
  (apply osc/osc-send (response-client incoming) path args))

(defonce
  ^{:private true
    :doc "Keeps track of peers that have been allocated to send streams to
  specified port numbers, so we can reuse them if there are multiple
  streams going to the same port on the same host.

  Keys are a tuple of the host and port to which the peer sends,
  values are a map holding the peer and count of active streams using
  it, so we can close the peer and remove the entry once the last
  stream is stopped."}
  custom-stream-clients
  (atom {}))

(defn- allocate-stream-client
  "Returns a peer that can be used to send to a custom port, creating it
  if this is the first one that has been requested for the given host
  and port pair, or incrementing the use count if an existing peer can
  be used."
  [host port]
  (let [updated (swap! custom-stream-clients update [host port]
                       (fn [entry]
                         (if entry
                           (update entry :count inc)
                           {:client (osc/osc-client host port)
                            :count  1})))]
    (get-in updated [[host port] :client])))

(defn- release-stream-client
  "Decrements the use count associated with a custom stream client,
  indicating that a stream is no longer using it. If the count reaches
  zero, the client is closed and removed from the map."
  [host port]
  (let [to-close (volatile! nil)]
    (swap! custom-stream-clients
           (fn [current]
             (if (> (get-in current [[host port] :count]) 1)
               (update-in current [[host port] :count] dec)
               (do
                 (vreset! to-close (get-in current [[host port] :client]))
                 (dissoc current [host port])))))
    (when @to-close
      (try
        (osc/osc-close @to-close)
        (catch Exception e
          (timbre/error e "Problem closing OSC client for host" host "port" port))))))

(defonce
  ^{:private true
    :doc "Keeps track of the OSC paths for which streaming updates have been
  requested, along with the destination information. Keys are the OSC
  path (for example `/devices` for device announcements) followed by
  `[host port]` tuples indicating a subscription to that stream;
  values are the client to which the updates should be sent. For
  subscriptions on a client's default response port, `port` will be
  the negation of the client port number, so these subscriptions can
  still be kept distinct, but we know not to try to release the client
  when the stream ends."}
  active-streams
  (atom {}))

(defn- respond-with-error
  "Send an error message to the appropriate response port for an
  incoming message, and also log it. Guaranteed to return `nil`."
  [incoming & args]
  (apply respond incoming "/error" args)
  (timbre/error (clojure.string/join " " args)))

(defn- parse-subscription-port
  "Determines the port on which a stream subscription should be
  recorded. If none was requested, use the negation of the incoming
  message port. Otherwise, use the port specified. If one was
  requested but not valid, return `nil`."
  [incoming port]
  (if (some? port)
    (if (and (int? port) (< 0 port 65536))
      port  ; A valid custom port, we will allocate a client for it.
      (let [message (str "Illegal port value for " (:path incoming) ", must be in range 1-65535: " port)]
        (respond-with-error incoming message)))  ; Returns nil to indicate illegal port requested.
    (- (:src-port incoming))))  ; Negative means use default response client.

(defn- make-subscription
  "Sets up the values needed for a stream subscription given an incoming
  message and optional port number. Returns `[[host port] client]`. If
  port is positive, it is the custom port requested, and `client` will
  be allocated for the use of this subscription. If negative, then the
  default response port was requested, and `client` is the standard
  response client associated with the incoming message. If a custom
  port value was requested but not valid, `nil` is returned."
  [incoming port]
  (when-let [subscription-port (parse-subscription-port incoming port)]
    (let [client (if (pos? subscription-port)
                   (allocate-stream-client (:src-host incoming) subscription-port)
                   (response-client incoming))]
      [[(:src-host incoming) subscription-port] client])))

(defn- unsubscribe
  "When a subscription is no longer needed, checks to see if its client
  was allocated for the subscription, and if so, releases it."
  [[host port]]
  (when (pos? port)
    (release-stream-client host port)))

(defn- stop-stream
  "Removes the stream with the incoming message path and subscription
  (`[host port]` as described above), releasing its client if one
  was allocated."
  [incoming port]
  (when-let [subscription-port (parse-subscription-port incoming port)]
    (let [subscription [(:src-host incoming) subscription-port]]
      (swap! active-streams update (:path incoming) dissoc subscription)
      (unsubscribe subscription))))

(defn- start-stream
  "Sets up a stream for the path of the received message, allocating a
  client for a custom port number if one was supplied, or using the
  standard response client for the incoming message otherwise. If
  `init-fn` is supplied, it will be called with the stream's OSC
  client once that has been established, and can send any desired
  initial stream messages."
  ([incoming port]
   (start-stream incoming port nil))
  ([incoming port init-fn]
   (when-let [[subscription client] (make-subscription incoming port)]
     (stop-stream incoming port)  ; In case there was a previous one in place
     (swap! active-streams assoc-in [(:path incoming) subscription] client)
     (when init-fn (init-fn client)))))

(defn build-device-announcement-args
  "Builds the OSC arguments used to report a device announcement."
  [^org.deepsymmetry.beatlink.DeviceAnnouncement announcement]
  [(.getNumber announcement) (.getName announcement) (.. announcement (getAddress) (getHostAddress))])

(defn- send-device-announcement
  "Sends a device announcement message to the specified client."
  [announcement client]
  (apply osc/osc-send client "/device/found" (build-device-announcement-args announcement)))

(defn- announce-current-devices
  "Helper function that sends a set of device annoucement messages for
  all devices currently present on the network."
  [client]
  (doseq [announcement (.getCurrentDevices device-finder)]
    (send-device-announcement announcement client)))

(defn- devices-handler
  "Handler for the /devices path, which obtains information about the
  devices currently on the network.

  Usage:
    /devices list port?    (sends current devices)
    /devices stream port?  (streams device updates until stopped)
    /devices stop port?    (stops streaming device updates)

  If `port` is omitted, the default response port is used."
  [{:keys [args] :as msg}]
  (let [[command port] args]
    (case command

      "list"
      (when-let [[subscription client] (make-subscription msg port)]
        (announce-current-devices client)
        (unsubscribe subscription))

      "stream"
      (start-stream msg port announce-current-devices)

      "stop"
      (stop-stream msg port)

      (respond-with-error msg (str "Unknown " (:path msg) " command:") command))))

(defn- update-osc-client
  "Sets an OSC client to send messages to the specified host and port.
  If the client does not yet exist, creates it."
  [client host port]
  (if client
    (osc/osc-target client host port)
    (osc/osc-client host port)))

(defn- respond-handler
  "Handler for the /respond path, which sets the port on which
  responses should be sent to a particular client.

  Usage:
    /respond port  (set response port for similar messages)
    /respond       (unset response port for similar messages)"
  [msg]
  (let [{:keys [src-host src-port args]} msg
        port                             (first args)]
    (if (some? port)  ; We were given a port value
      (if (and (int? port)
               (< 0 port 65536))
        (swap! response-map update [src-host src-port] update-osc-client src-host port)
        (timbre/error "Illegal port value for /respond, must be in range 1-65535, ignoring:" port))
      (swap! response-map update [src-host src-port] update-osc-client src-host (inc src-port)))

    (timbre/info "Will respond to messages from host" src-host "and port" src-port "on port"
                 (response-port msg))))

(defn- log-handler
  "Handler for the /log command, which simply logs the received message.

  Usage:
    /log args..."
  [msg]
  (timbre/info "Received /log message:" msg))

(defn open-server
  "Starts our server listening on the specified port number, and
  registers all the message handlers for messages we support."
  [port]
  (swap! server (fn [oldval]
                  (when oldval (throw (ex-info "Server already open" {:server oldval})))
                  (osc/osc-server port "Open Beat Control OSC")))
  (osc/osc-handle @server "/respond" respond-handler)
  (osc/osc-handle @server "/log" log-handler)
  (osc/osc-handle @server "/devices" devices-handler))

(defn publish-to-stream
  "Sends an OSC message to all clients subscribed to a particular
  subscription path."
  [subscription-path send-path & args]
  (doseq [client (vals (get @active-streams subscription-path))]
    (apply osc/osc-send client send-path args)))