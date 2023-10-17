(ns open-beat-control.core
  "The main entry point for the open-beat-control daemon. Handles any
  command-line arguments, then establishes and interacts with
  connections to any Pioneer Pro DJ Link session that can be found,
  providing remote control via an OSC server."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [open-beat-control.logs :as logs]
            [open-beat-control.osc-server :as server]
            [open-beat-control.util :as util :refer [device-finder virtual-cdj beat-finder metadata-finder
                                                     signature-finder time-finder crate-digger]]
            [beat-carabiner.core :as carabiner]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceAnnouncementListener BeatListener DeviceUpdate
            MasterListener DeviceUpdateListener Util CdjStatus MixerStatus]
           [org.deepsymmetry.beatlink.data TrackMetadataListener SignatureListener])
  (:gen-class))

(defn- println-err
  "Prints objects to stderr followed by a newline."
  [& more]
  (binding [*out* *err*]
    (apply println more)))

(def ^:private log-file-error
  "Holds the validation failure message if the log file argument was
  not acceptable."
  (atom nil))

(def ^:private ^String system-newline
  "Holds the appropriate string for moving the terminal to the start of
  a new line on the current platform."
  (System/getProperty "line.separator"))

(defn- bad-log-arg
  "Records a validation failure message for the log file argument, so
  a more specific diagnosis can be given to the user. Returns false to
  make it easy to invoke from the validation function, to indicate
  that validation failed after recording the reason."
  [& messages]
  (reset! log-file-error (str/join " " messages))
  false)

(defn- valid-log-file?
  "Check whether a string identifies a file that can be used for logging."
  [path]
  (let [f (io/file path)
        dir (or (.getParentFile f) (.. f (getAbsoluteFile) (getParentFile)))]
    (if (.exists f)
      (cond  ; The file exists, so make sure it is writable and a plain file
        (not (.canWrite f)) (bad-log-arg "Cannot write to log file")
        (.isDirectory f) (bad-log-arg "Requested log file is actually a directory")
        ;; Requested existing file looks fine, make sure we can roll over
        :else (or (.canWrite dir)
                  (bad-log-arg "Cannot create rollover log files in directory" (.getPath dir))))
      ;; The requested file does not exist, make sure we can create it
      (if (.exists dir)
        (and (or (.isDirectory dir)
                 (bad-log-arg "Log directory is not a directory:" (.getPath dir)))
             (or (.canWrite dir) ; The parent directory exists, make sure we can write to it
                 (bad-log-arg "Cannot create log file in directory" (.getPath dir))))
        (or (.mkdirs dir) ; The parent directory doesn't exist, make sure we can create it
          (bad-log-arg "Cannot create log directory" (.getPath dir)))))))

(def cli-options
  "The command-line options supported by Open Beat Control."
  [["-o" "--osc-port PORT" "Port number for OSC server"
    :default 17002
    :parse-fn #(Long/parseLong %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-r" "--real-player" "Try to pose as a real CDJ (device #1-4)"]
   ["-d" "--device-number NUM" "Use fixed device # (overrides -r)"
    :parse-fn #(Long/parseLong %)
    :validate [#(<= 1 % 127) "Must be a number between 1 and 127"]]
   ["-B" "--bridge" "Use Carabiner to bridge to Ableton Link"]
   ["-a" "--ableton-master" "When bridging, Ableton Link tempo wins"]
   ["-b" "--beat-align" "When bridging, sync to beats only, not bars"]
   ["-c" "--carabiner-port PORT" "When bridging, port # of Carabiner daemon"
    :default 17000
    :parse-fn #(Long/parseLong %)
    :validate [#(< 0 % 0x10000) "Must be a number between 1 and 65535"]]
   ["-l" "--latency MS" "How many milliseconds are we behind the CDJs"
    :default 20
    :parse-fn #(Long/parseLong %)
    :validate [#(<= 0 % 500) "Must be a number between 0 and 500 inclusive"]]
   ["-L" "--log-file PATH" "Log to a rotated file instead of stdout"
    :validate [valid-log-file? @log-file-error]]
   ["-h" "--help" "Display help information and exit"]])

(defn usage
  "Print message explaining command-line invocation options."
  [options-summary]
  (str/join
   system-newline
   [(str "open-beat-control, provides OSC access to a subset of beat-link.")
    (str "Usage: open-beat-control [options]")
    ""
    "Options:"
    options-summary
    ""
    "See https://github.com/Deep-Symmetry/open-beat-control for user guide."]))

(defn error-msg
  "Format an error message related to command-line invocation."
  [errors]
  (str "The following errors occurred while parsing your command:"
       system-newline system-newline
       (str/join system-newline errors)))

(defn- validate-combinations
  "Check for mutual inconsistencies between supplied options, now that
  all have been recorded."
  [options]
  (let [fixed (:device-number options)]
    (filter identity
            [(when (and (:ableton-master options)
                        (if fixed
                          (> fixed 4)
                          (not (:real-player options))))
               "Inconsistent options: ableton-master mode requires a real player number (1-4).")])))

(defn exit
  "Terminate execution with a message to the command-line user."
  [status msg]
  (if (zero? status)
    (println msg)
    (println-err msg))
  (System/exit status))

(defn- start-other-finders
  "Called when the Virtual CDJ has started successfully, to start up the
  full complement of metadata-related finders that we use."
  []
  (timbre/info "Virtual CDJ running as Player" (.getDeviceNumber virtual-cdj))
  (.start metadata-finder)
  (.start crate-digger)
  (.start signature-finder)
  (.start time-finder))

(defn- establish-bridge-mode
  "Once we have both a Carabiner connection and the VirtualCDJ is
  online, it is time to set the sync mode the user requested via
  command line options, if possible. `ableton-master?` indicates
  whether the Ableton Link session is supposed to be the tempo
  master."
  [ableton-master?]
  (if (and ableton-master? (.isSendingStatus virtual-cdj))
    (do
      (.becomeTempoMaster virtual-cdj)
      (when-let [current-tempo (:link-bpm (carabiner/state))]
        (.setTempo virtual-cdj current-tempo))
      (carabiner/set-sync-mode :full))
    (do
      (.setSynced virtual-cdj true)
      (carabiner/set-sync-mode :passive))))

(defn- connect-carabiner
  "Called when we are supposed to bridge to an Ableton Link session and
  so need a Carabiner daemon connection. `ableton-master?` indicates
  whether the Ableton Link session is supposed to be the tempo master."
  [ableton-master?]
  (loop []
    (let [{:keys [port latency]} (carabiner/state)]
      (timbre/info "Trying to connect to Carabiner daemon on port" port "with latency" latency)
      (when-not (try
                  (carabiner/connect)
                  (catch Throwable t
                    (timbre/error t "Problem trying to connect to Carabiner.")))
        (timbre/warn "Not connected to Carabiner. Waiting ten seconds to try again.")
        (Thread/sleep 10000)
        (recur))))
  (when (.isRunning virtual-cdj) (establish-bridge-mode ableton-master?)))

(defn -main
  "The entry point when invoked as a jar from the command line. Parse
  options, and start daemon operation."
  [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (let [errors (concat errors (map #(str "Unknown argument: " %) arguments)
                         (validate-combinations options))]

      ;; Handle help and error conditions.
      (cond
        (:help options) (exit 0 (usage summary))
        (seq errors)    (exit 1 (str (error-msg errors) "\n\n" (usage summary)))))

    ;; Set up the logging environment.
    (logs/init-logging (:log-file options))

    ;; Create our OSC server to respond to commands.
    (server/open-server (:osc-port options))
    (timbre/info "Running OSC server on port" (:osc-port options))

    ;; See if the user wants us to use a specific player number.
    (if-let [fixed (:device-number options)]
      (timbre/info (str "Virtual CDJ will attempt to use device #" fixed))
      (when (:real-player options)  ; Auto-assigning number; should we try to use the real player range?
        (.setUseStandardPlayerNumber virtual-cdj true)
        (timbre/info "Virtual CDJ will attempt to pose as a standard player, device #1 through #4")))

    ;; Set our device name.
    (.setDeviceName virtual-cdj "open-beat-control")

    ;; Start the daemons that do everything!
    (timbre/info "Waiting for Pro DJ Link devices...")

    ;; Start watching for any Pro DJ Link devices.
    (.start device-finder)
    (.addDeviceAnnouncementListener  ; And set up to respond when they arrive and leave.
     device-finder
     (reify DeviceAnnouncementListener
       (deviceFound [_ announcement]
         (apply server/publish-to-stream "/devices" "/device/found"  ; Report the device to OSC subsribers.
                (server/build-device-announcement-args announcement))
         (timbre/info "Pro DJ Link Device Found:" announcement)
         (future  ; We have seen a device, so we can start up the Virtual CDJ if it's not running.
           (try
             (when (not (.isRunning virtual-cdj))
               (if (.start virtual-cdj (byte (:device-number options 0)))
                 (do (start-other-finders)
                     (.setPassive metadata-finder true)  ; Start out conservatively...
                     (when (util/real-player?)  ; But no, we can use all the bells and whistles!
                       (.setSendingStatus virtual-cdj true)
                       (.setPassive metadata-finder false))
                     (when (and (:bridge options) (carabiner/active?) (not (carabiner/sync-enabled?)))
                       (establish-bridge-mode (:ableton-master options))))
                 (timbre/warn "Virtual CDJ failed to start.")))
             (catch Throwable t
               (timbre/error t "Problem trying to start Victual CDJ.")))))
       (deviceLost [_ announcement]
         (apply server/publish-to-stream "/devices" "/device/lost"
                (server/build-device-announcement-args announcement))
         (server/purge-device-state (.getDeviceNumber announcement))
         (timbre/info "Pro DJ Link Device Lost:" announcement)
         (when (empty? (.getCurrentDevices device-finder))
           (timbre/info "Shutting down Virtual CDJ.")  ; We have lost the last device, so shut down for now.
           (.stop virtual-cdj)))))

    ;; Be ready for status packets once the VirtualCdj is running.
    (.addUpdateListener
     virtual-cdj
     (reify DeviceUpdateListener
       (received [_ status]
         (let [device (.getDeviceNumber status)]
           (when (not= 0xffff (.getBpm status))  ; If packet has a valid tempo, process it.
             (let [tempo  (float (.getEffectiveTempo status))]
               (when (server/update-device-state device :tempo tempo)
                 (server/publish-to-stream "/tempos" (str "/tempo/" device) tempo))))
           (when (instance? CdjStatus status)  ; Manage CDJ-only streams

             (let [playing (util/boolean-to-osc (.isPlaying ^CdjStatus status))]
               (when (server/update-device-state device :playing playing)
                 (server/publish-to-stream "/playing" (str "/playing/" device) playing))
               ;; See if this is also a change to the playing state of the tempo master
               (when-let [^DeviceUpdate master-update (and (.isRunning virtual-cdj) (.getTempoMaster virtual-cdj))]
                 (when (and (= (.getDeviceNumber master-update) device)
                            (server/update-device-state :master :master-playing playing))
                   (server/publish-to-stream "/master" "/master/playing" playing))))

             (let [on-air (util/boolean-to-osc (.isOnAir ^CdjStatus status))]
               (when (server/update-device-state device :on-air on-air)
                 (server/publish-to-stream "/on-air" (str "/on-air/" device) on-air)))

             (let [loaded (util/build-loaded-state status)]
               (when (server/update-device-state device :loaded loaded)
                 (apply server/publish-to-stream "/loaded" (str "/loaded/" device) loaded)))

             (let [cued (util/boolean-to-osc (.isCued ^CdjStatus status))]
               (when (server/update-device-state device :cued cued)
                 (server/publish-to-stream "/cued" (str "/cued/" device) cued))))

           (when (or (instance? CdjStatus status) (instance? MixerStatus status))  ; Manage status-only streams.
             (let [synced (if (.isSynced status) (int 1) (int 0))]
               (when (server/update-device-state device :synced synced)
                 (server/publish-to-stream "/synced" (str "/sync/" device) synced))))))))

    ;; Also start watching for beat packets.
    (.start beat-finder)
    (.addBeatListener  ; And set up to respond to them.
     beat-finder
     (reify BeatListener
       (newBeat [_ beat]
         (let [device (.getDeviceNumber beat)
               tempo  (float (.getEffectiveTempo beat))]
           (server/publish-to-stream "/beats" "/beat" device (.getBeatWithinBar beat) tempo
                                     (float (/ (.getBpm beat) 100.0))
                                     (float (Util/pitchToMultiplier (.getPitch beat))))
           (when (server/update-device-state device :tempo tempo)
             (server/publish-to-stream "/tempos" (str "/tempo/" device) tempo))
           (when (<= 1 device 4)  ; Beats from CDJs might be first indication they are playing.
             (when (server/update-device-state device :playing (int 1))
               (server/publish-to-stream "/playing" (str "/playing" device) (int 1)))
             ;; A beat from the tempo master might also be the first indication it is playing.
             (when-let [^DeviceUpdate master-update (and (.isRunning virtual-cdj) (.getTempoMaster virtual-cdj))]
               (when (and (= (.getDeviceNumber master-update) device)
                          (server/update-device-state :master :master-playing (int 1)))
                 (server/publish-to-stream "/master" "/master/playing" (int 1)))))))))

    ;; And register for tempo master changes, so we can pass them on to any subscribers.
    (.addMasterListener
     virtual-cdj
     (reify MasterListener
       (newBeat [_ beat]
         (server/publish-to-stream "/master" "/master/beat" (.getDeviceNumber beat) (.getBeatWithinBar beat)
                                   (float (.getEffectiveTempo beat)) (float (/ (.getBpm beat) 100.0))
                                   (float (Util/pitchToMultiplier (.getPitch beat)))))
       (masterChanged [_ device-update]
         (if device-update
           (let [device (.getDeviceNumber device-update)]  ; We have a new tempo master device.
             (server/publish-to-stream "/master" "/master/player" device)
             (server/update-device-state :master :master-player device)
             (when (> device 4) ; Not a CDJ, so we don't consider the master to be playing any longer.
               (when (server/update-device-state :master :master-playing (int 0))
                 (server/publish-to-stream "/master" "/master/playing" (int 0)))))
           (do  ; There is no longer a tempo master on the network.
             (server/publish-to-stream "/master" "/master/none")
             (when (server/update-device-state :master :master-playing (int 0))
               (server/publish-to-stream "/master" "/master/playing" (int 0)))
             (server/purge-master-state))))
       (tempoChanged [_ tempo]
         (let [tempo (float tempo)]
           (server/publish-to-stream "/master" "/master/tempo" tempo)))))

    ;; And register for metadata updates, so we can pass those on to subscribers.
    (.addTrackMetadataListener
     metadata-finder
     (reify TrackMetadataListener
       (metadataChanged [_ md-update]
         (server/publish-metadata-messages md-update))))

    ;; Similarly register and relay track signature updates.
    (.addSignatureListener
     signature-finder
     (reify SignatureListener
       (signatureChanged [_ sig-update]
         (server/publish-to-stream "/signatures" (str "/signature/" (.player sig-update))
                                   (or (.signature sig-update) "none")))))

    ;; Configure Carabiner options
    (carabiner/set-carabiner-port (:carabiner-port options))
    (carabiner/set-latency (:latency options))
    (carabiner/set-sync-bars (not (:beat-align options)))
    (when (:bridge options)  ; We are supposed to bridge to Carabiner.
      (carabiner/add-disconnection-listener (fn [_] (connect-carabiner (:ableton-master options))))
      (connect-carabiner (:ableton-master options)))))
