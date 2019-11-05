(ns open-beat-control.core
  "The main entry point for the open-beat-control daemon. Handles any
  command-line arguments, then establishes and interacts with
  connections to any Pioneer Pro DJ Link session that can be found,
  providing remote control via an OSC server."
  (:require [clojure.tools.cli :as cli]
            [open-beat-control.logs :as logs]
            [open-beat-control.osc-server :as server]
            [open-beat-control.util :as util :refer [device-finder virtual-cdj beat-finder]]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink DeviceFinder DeviceAnnouncementListener BeatFinder BeatListener
                                      VirtualCdj MasterListener DeviceUpdateListener Util CdjStatus])
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

(defn- bad-log-arg
  "Records a validation failure message for the log file argument, so
  a more specific diagnosis can be given to the user. Returns false to
  make it easy to invoke from the validation function, to indicate
  that validation failed after recording the reason."
  [& messages]
  (reset! log-file-error (clojure.string/join " " messages))
  false)

(defn- valid-log-file?
  "Check whether a string identifies a file that can be used for logging."
  [path]
  (let [f (clojure.java.io/file path)
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
   ["-L" "--log-file PATH" "Log to a rotated file instead of stdout"
    :validate [valid-log-file? @log-file-error]]
   ["-h" "--help" "Display help information and exit"]])

(defn usage
  "Print message explaining command-line invocation options."
  [options-summary]
  (clojure.string/join
   \newline
   [(str "open-beat-control, provides OSC access to a subset of beat-link.")
    (str "Usage: open-beat-control [options]")
    ""
    "Options:"
    options-summary
    ""
    "Please see https://github.com/Deep-Symmetry/open-beat-control for more information."]))

(defn error-msg
  "Format an error message related to command-line invocation."
  [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(defn exit
  "Terminate execution with a message to the command-line user."
  [status msg]
  (if (zero? status)
    (println msg)
    (println-err msg))
  (System/exit status))

(defn -main
  "The entry point when invoked as a jar from the command line. Parse
  options, and start daemon operation."
  [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (let [errors (concat errors (map #(str "Unknown argument: " %) arguments))]

      ;; Handle help and error conditions.
      (cond
        (:help options) (exit 0 (usage summary))
        (seq errors)    (exit 1 (str (error-msg errors) "\n\n" (usage summary)))))

    ;; Set up the logging environment.
    (logs/init-logging (:log-file options))

    ;; Create our OSC server to respond to commands.
    (server/open-server (:osc-port options))
    (timbre/info "Running OSC server on port" (:osc-port options))

    ;; See if the user wants us to use a real player number.
    (when (:real-player options)
      (.setUseStandardPlayerNumber virtual-cdj true)
      (timbre/info "Virtual CDJ will attempt to pose as a standard player, device #1 through #4"))

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
           (if (.start virtual-cdj)
             (timbre/info "Virtual CDJ running as Player" (.getDeviceNumber virtual-cdj))
             (timbre/warn "Virtual CDJ failed to start."))))
       (deviceLost [_ announcement]
         (apply server/publish-to-stream "/devices" "/device/lost"
                (server/build-device-announcement-args announcement))
         (server/purge-device-state (.getDeviceNumber announcement))
         (timbre/info "Pro DJ Link Device Lost:" announcement)
         (when (empty? (.getCurrentDevices device-finder))
           (timbre/info "Shutting down Virtual CDJ.")  ; We have lost the last device, so shut down for now.
           (.stop virtual-cdj)
           #_(carabiner/unlock-tempo)))))

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
           (when (instance? CdjStatus status)  ; Manage CDJ-only streams.
             (let [playing (if (.isPlaying status) (int 1) (int 0))]
               (when (server/update-device-state device :playing playing)
                 (server/publish-to-stream "/playing" (str "/playing/" device) playing))))))))

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
               (server/publish-to-stream "/playing" (str "/playing" device) (int 1))))))))

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
           (server/publish-to-stream "/master" "/master/player" (.getDeviceNumber device-update))
           (server/publish-to-stream "/master" "/master/none")))
       (tempoChanged [_ tempo]
         (let [tempo (float tempo)]
           (server/publish-to-stream "/master" "/master/tempo" tempo)))))))
