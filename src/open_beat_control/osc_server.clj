(ns open-beat-control.osc-server
  "Manages the OSC server used to offer Beat Link services."
  (:require [overtone.osc :as osc]
            [open-beat-control.util :as util :refer [device-finder virtual-cdj beat-finder]]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink CdjStatus$TrackType CdjStatus$TrackSourceSlot]))

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

(defonce ^{:private true
           :doc "Holds last known interesting state elements of
  players on the network, so we can report when changes happen. Top
  level keys identify the category of interesting information; in
  general, these are maps whose keys are the devices themselves, and
  values are the last-known information for that device. There is also
  a `:last` key which is used by `update-device-state` to save
  previous state elements when updating them in order to check whether
  they actually changed."}
  state
  (atom {}))

(defn purge-device-state
  "Gets rid of any saved state about the specified device, presumably
  because it has disappeared."
  [device]
  (swap! state
         (fn [current]
           (-> current
               (update :playing dissoc device)
               (update :tempo dissoc device)))))

(defn update-device-state
  "Sets some element of interesting device state. Returns truthy if this
  represents a change from the previous state."
  [device kind value]
  (let [updated (swap! state
                       (fn [current]
                         (-> current
                             (assoc-in [:last kind device] (get-in current [kind device]))
                             (assoc-in [kind device] value))))]
    (not= (get-in updated [:last kind device]) value)))

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

(defn- streamable-handler
  "Generic handler for the most common command pattern, which supports
  listing, streaming, and stopping the stream of a category of
  information, where `/path` is something like `/devices` or
  `/tempos`.

  The pattern is:
    /path list port?    (sends current state of the category)
    /path stream port?  (streams category updates until stopped)
    /path stop port?    (stops streaming category updates)

  If `port` is omitted, the default response port is used.

  Arguments to this function are the OSC message that was received for
  the handler, and a function that can be called with an OSC client to
  send the current state of the category of information that the
  handler is responsible for reporting.

  If `extra-command-handler` is supplied, it will be called with the
  message arguments and the full message when an unrecognized command
  is received. If it handles it successfully, it must return a truthy
  value. If there is no extra command handler, or it returns falsey,
  an error will be reported about the unrecognized command."
  ([msg send-current-state]
   (streamable-handler msg send-current-state nil))
  ([{:keys [args] :as msg} send-current-state extra-command-handler]
   (or (and extra-command-handler (extra-command-handler args msg))
       (let [[command port] args]
         (case command

           "list"
           (when-let [[subscription client] (make-subscription msg port)]
             (send-current-state client)
             (unsubscribe subscription))

           "stream"
           (start-stream msg port send-current-state)

           "stop"
           (stop-stream msg port)

           nil
           (respond-with-error msg (str (:path msg) " requires a command."))

           (respond-with-error msg (str "Unknown " (:path msg) " command:") command))))))

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
  "Standad streaming handler for the /devices path, which obtains
  information about the devices currently on the network."
  [msg]
  (streamable-handler msg announce-current-devices))

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
  [{:keys [src-host src-port args] :as msg}]
  (let [port (first args)]
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

(defn- logging-handler
  "Handler for the /logging command, which asks for any future OBC log
  file entries to be also sent as a /log response to this message.

  Usage:
    /logging stream port?  (streams log file entries until stopped)
    /logging stop port?    (stops streaming log events)

  If `port` is omitted, the default response port is used."
  [{:keys [args] :as msg}]
  (let [[command port] args]
    (case command

      "stream"
      (start-stream msg port)

      "stop"
      (stop-stream msg port)

      nil
      (respond-with-error msg (str (:path msg) " requires a command."))

      (respond-with-error msg (str "Unknown " (:path msg) " command:") command))))

(defn- beats-handler
  "Handler for the /beats command, which configures the reporting of
  beat packets seen on the network as /beat responses.

  Usage:
    /beats stream port? (streams beats until stopped)
    /beats stop port? (stops streaming beats)

  If `port` is omitted, the default response port is used."
  [{:keys [args] :as msg}]
  (let [[command port] args]
    (case command

      "stream"
      (start-stream msg port)

      "stop"
      (stop-stream msg port)

      nil
      (respond-with-error msg (str (:path msg) " requires a command."))

      (respond-with-error msg (str "Unknown " (:path msg) " command:") command))))

(defn- appoint-master
  "Helper function for requests to appoint a new player as Tempo
  Master."
  [msg player]
  (if (integer? player)
    (try
      (.appointTempoMaster virtual-cdj player)
      (catch IllegalArgumentException e
        (respond-with-error msg (.getMessage e))))
    (respond-with-error msg (str (:path msg) "appoint requires an integer player number."))))

(defn- announce-current-master
  "Helper function that sends whatever is known about the tempo master
  state."
  [client]
  (if-let [master (.getTempoMaster virtual-cdj)]
    (do
      (osc/osc-send client "/master/player" (.getDeviceNumber master))
      (osc/osc-send client "/master/tempo" (float (.getMasterTempo virtual-cdj))))
    (osc/osc-send client "/master/none")))

(defn- master-handler
  "Standard stream handler for the /master command, which configures the
  reporting of Tempo Master changes seen on the network. Also supports
  an `appoint` command which appoints a new Master:
    /master appoint player (tells player to become Tempo Master)"
  [msg]
  (streamable-handler msg announce-current-master
                      (fn [args msg]
                        (let [[command port-or-player] args]
                          (when (= command "appoint")
                            (appoint-master msg port-or-player)
                            true)))))

(defn- announce-current-tempos
  "Helper function that sends a set of tempo messages for all devices
  whose tempos are currently known."
  [client]
  (let [current @state]
    (doseq [device (keys (:tempo current))]
      (osc/osc-send client (str "/tempo/" device) (get-in current [:tempo device])))))

(defn- tempos-handler
  "Standard streaming handler for the /tempos path, which obtains
  information about the tempos currently on the network."
  [msg]
  (streamable-handler msg announce-current-tempos))

(defn- announce-current-playing-states
  "Helper function that sends a set of playing messages for all devices
  whose playing states are currently known."
  [client]
  (let [current @state]
    (doseq [device (keys (:playing current))]
      (osc/osc-send client (str "/playing/" device) (get-in current [:playing device])))))

(defn- playing-handler
  "Standard streaming handler for the /playing path, which obtains
  information about the playing states of devices on the network."
  [msg]
  (streamable-handler msg announce-current-playing-states))

(defn- announce-current-sync-states
  "Helper function that sends a set of synced messages for all devices
  whose sync states are currently known."
  [client]
  (let [current @state]
    (doseq [device (keys (:synced current))]
      (osc/osc-send client (str "/sync/" device) (get-in current [:synced device])))))

(defn- synced-handler
  "Standard streaming handler for the /synced path, which obtains
  information about the sync state of devices on the network."
  [msg]
  (streamable-handler msg announce-current-sync-states))

(defn- announce-current-on-air-states
  "Helper function that sends a set of on-air messages for all devices
  whose on-air states are currently known."
  [client]
  (let [current @state]
    (doseq [device (keys (:on-air current))]
      (osc/osc-send client (str "/on-air/" device) (get-in current [:on-air device])))))

(defn- on-air-handler
  "Standard streaming handler for the /on-air path, which obtains
  information about the on-air state of devices on the network."
  [msg]
  (streamable-handler msg announce-current-on-air-states))

(defn gather-on-off-sets
  "Helper for collecting arguments of commands that turn on and off
  features of sets of players. Each positive integer in the arguments
  is collected in the `on` set, and negative integers' absolute values
  are collected in the `off` set. Both sets are returned as a tuple."
  [args]
  (reduce (fn [[on off] arg]
            (cond
              (pos-int? arg)
              [(conj on (int arg)) off]

              (neg-int? arg)
              [on (conj off (int (- arg)))]

              :else
              [on off]))
          [#{} #{}]
          args))

(defn- fader-start-handler
  "Sends a fader start message to start some players (as long as they
  are paused at the cue point) and stop others. Send positive player
  numbers to try starting them, and negative player numbers to try
  stopping them."
  [msg]
  (let [[start stop] (gather-on-off-sets (:args msg))]
    (try
      (.sendFaderStartCommand virtual-cdj start stop)
      (catch Exception e
        (respond-with-error msg "Problem sending fader start command" (.getMessage e))))))

(defn- set-sync-handler
  "Turns sync mode on or off for player numbers passed as
  arguments (positive to turn on, negative to turn off)."
  [msg]
  (let [[on off] (gather-on-off-sets (:args msg))]
    (doseq [device on]
      (try
        (.sendSyncModeCommand virtual-cdj device true)
        (catch Exception e
          (respond-with-error msg (str "Unable to turn on sync for device" device ":") (.getMessage e)))))
    (doseq [device off]
      (try
        (.sendSyncModeCommand virtual-cdj device false)
        (catch Exception e
          (respond-with-error msg (str "Unable to turn off sync for device" device ":") (.getMessage e)))))))

(defn- set-on-air-handler
  "Turns the on-air state on or off for player numbers passed as
  arguments (positive to turn on, negative to turn off)."
  [msg]
  (let [[on _] (gather-on-off-sets (:args msg))]
    (try
      (.sendOnAirCommand virtual-cdj on)
      (catch Exception e
        (respond-with-error msg "Problem sending on-air command" (.getMessage e))))))

(defn- announce-current-loaded-states
  "Helper function that sends a set of loaded messages for all devices
  whose loaded states are currently known."
  [client]
  (let [current @state]
    (doseq [device (keys (:on-air current))]
      (apply osc/osc-send client (str "/loaded/" device) (get-in current [:loaded device])))))

(defn- loaded-handler
  "Standard streaming handler for the /loaded path, which obtains
  information about the track load state of devices on the network."
  [msg]
  (streamable-handler msg announce-current-loaded-states))

(defn- match-slot
  "Returns the enum value corresponding to a slot name. Sends an error
  response to the incoming `msg` and returns NO_TRACK if the name is
  not recognized."
  [slot msg]
  (case slot
    "cd" CdjStatus$TrackSourceSlot/CD_SLOT
    "sd" CdjStatus$TrackSourceSlot/SD_SLOT
    "usb" CdjStatus$TrackSourceSlot/USB_SLOT
    "collection" CdjStatus$TrackSourceSlot/COLLECTION
    (do
      (respond-with-error msg "Urecognized slot name:" slot)
      CdjStatus$TrackSourceSlot/NO_TRACK)))

(defn- match-track-type
  "Returns the Enum value corresponding to a track type name. Sends an
  error response to the incoming `msg` and returns NO_TRACK if the
  name is not recognized."
  [track-type msg]
  (case track-type
    "cd" CdjStatus$TrackType/CD_DIGITAL_AUDIO
    "rekordbox" CdjStatus$TrackType/REKORDBOX
    "unanalyzed" CdjStatus$TrackType/UNANALYZED
    (do
      (respond-with-error msg "Urecognized track type name:" track-type)
      CdjStatus$TrackType/NO_TRACK)))

(defn- load-handler
  "Sends a request to a player to have it load a track."
  [{:keys [args] :as msg}]
  (let [[target-player source-device slot track-type id] args
        slot                                             (match-slot slot msg)
        track-type                                       (match-track-type track-type msg)]
    (try
      (.sendLoadTrackCommand virtual-cdj (int target-player) (int id) (int source-device) slot track-type)
      (catch Exception e
        (respond-with-error msg "Problem sending load track command" (.getMessage e))))))

(defn open-server
  "Starts our server listening on the specified port number, and
  registers all the message handlers for messages we support."
  [port]
  (swap! server (fn [oldval]
                  (when oldval (throw (ex-info "Server already open" {:server oldval})))
                  (osc/osc-server port "Open Beat Control OSC")))
  (osc/osc-handle @server "/respond" respond-handler)
  (osc/osc-handle @server "/log" log-handler)
  (osc/osc-handle @server "/logging" logging-handler)
  (osc/osc-handle @server "/devices" devices-handler)
  (osc/osc-handle @server "/beats" beats-handler)
  (osc/osc-handle @server "/master" master-handler)
  (osc/osc-handle @server "/tempos" tempos-handler)
  (osc/osc-handle @server "/playing" playing-handler)
  (osc/osc-handle @server "/synced" synced-handler)
  (osc/osc-handle @server "/set-sync" set-sync-handler)
  (osc/osc-handle @server "/on-air" on-air-handler)
  (osc/osc-handle @server "/set-on-air" set-on-air-handler)
  (osc/osc-handle @server "/fader-start" fader-start-handler)
  (osc/osc-handle @server "/loaded" loaded-handler)
  (osc/osc-handle @server "/load" load-handler))

(defn publish-to-stream
  "Sends an OSC message to all clients subscribed to a particular
  subscription path."
  [subscription-path send-path & args]
  (doseq [client (vals (get @active-streams subscription-path))]
    (apply osc/osc-send client send-path args)))
