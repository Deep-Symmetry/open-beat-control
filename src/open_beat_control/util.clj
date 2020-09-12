(ns open-beat-control.util
  "Holds utility functions and values, with few dependencies, so they
  can be easily required by other namespaces."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [org.deepsymmetry.beatlink DeviceFinder BeatFinder VirtualCdj
            CdjStatus CdjStatus$TrackType CdjStatus$TrackSourceSlot]
           [org.deepsymmetry.beatlink.data MetadataFinder CrateDigger
            SignatureFinder TimeFinder]))

(def ^DeviceFinder device-finder
  "Holds the singleton instance of the Device Finder for convenience."
  (DeviceFinder/getInstance))

(def ^VirtualCdj virtual-cdj
  "Holds the singleton instance of the Virtual CDJ for convenience."
  (VirtualCdj/getInstance))

(def ^BeatFinder beat-finder
  "Holds the singleton instance of the Beat Finder for convenience."
  (BeatFinder/getInstance))

(def ^MetadataFinder metadata-finder
  "Holds the singleton instance of the Metadata Finder for convenience."
  (MetadataFinder/getInstance))

(def ^SignatureFinder signature-finder
  "Holds the singleton instance of the Signature Finder for convenience."
  (SignatureFinder/getInstance))

(def ^TimeFinder time-finder
  "Holds the singleton instance of the Time Finder for convenience."
  (TimeFinder/getInstance))

(def ^CrateDigger crate-digger
  "Holds the singleton instance of the Crate Digger bridge for
  convenience."
  (CrateDigger/getInstance))

(defn ensure-online
  "Throws an exception if the virtual CDJ is not running."
  []
  (when-not (.isRunning virtual-cdj)
    (throw (IllegalStateException. "Virtual CDJ must be online to perform this operation."))))

(defn real-player?
  "Checks whether the virtual CDJ is using a real player number. Can
  only be called once it is online, so the answer is meaningful."
  []
  (ensure-online)
  (<= 1 (.getDeviceNumber virtual-cdj) 4))

(def ^:private project-version
  (delay (edn/read-string (slurp (io/resource "open_beat_control/version.edn")))))

(defn get-version
  "Returns the version information set up by lein-v."
  []
  (:version @project-version))

(defn get-java-version
  "Returns the version of Java in which we are running."
  []
  (str (System/getProperty "java.version")
       (when-let [vm-name (System/getProperty "java.vm.name")]
         (str ", " vm-name))
       (when-let [vendor (System/getProperty "java.vm.vendor")]
         (str ", " vendor))))

(defn get-os-version
  "Returns the operating system and version in which we are running."
  []
  (str (System/getProperty "os.name") " " (System/getProperty "os.version")))

(defn get-build-date
  "Returns the date this jar was built, if we are running from a jar."
  []
  (let [a-class    (class get-version)
        class-name (str (.getSimpleName a-class) ".class")
        class-path (str (.getResource a-class class-name))]
    (when (str/starts-with? class-path "jar")
      (let [manifest-path (str (subs class-path 0 (inc (clojure.string/last-index-of class-path "!")))
                               "/META-INF/MANIFEST.MF")]
        (with-open [stream (.openStream (java.net.URL. manifest-path))]
          (let [manifest   (java.util.jar.Manifest. stream)
                attributes (.getMainAttributes manifest)]
            (.getValue attributes "Build-Timestamp")))))))

(defmacro case-enum
  "Like `case`, but explicitly dispatch on Java enum ordinals."
  {:style/indent 1}
  [e & clauses]
  (letfn [(enum-ordinal [e] `(let [^Enum e# ~e] (.ordinal e#)))]
    `(case ~(enum-ordinal e)
       ~@(concat
          (mapcat (fn [[test result]]
                    [(eval (enum-ordinal test)) result])
                  (partition 2 clauses))
          (when (odd? (count clauses))
            (list (last clauses)))))))

(defn boolean-to-osc
  "Translates a truthy value to an int 0 or 1 value which is convenient
  as an OSC v1 argument as generally used by Max/MSP."
  [val]
  (if val (int 1) (int 0)))

(defn build-loaded-state
  "Returns a tuple describing the track, if any, loaded in the CDJ that
  sent a status packet."
  [^CdjStatus status]
  (let [source-player (.getTrackSourcePlayer status)
        source-slot   (case-enum (.getTrackSourceSlot status)
                        CdjStatus$TrackSourceSlot/NO_TRACK "none"
                        CdjStatus$TrackSourceSlot/CD_SLOT "cd"
                        CdjStatus$TrackSourceSlot/SD_SLOT "sd"
                        CdjStatus$TrackSourceSlot/USB_SLOT "usb"
                        CdjStatus$TrackSourceSlot/COLLECTION "collection"
                        "unknown")
        track-type    (case-enum (.getTrackType status)
                        CdjStatus$TrackType/NO_TRACK "none"
                        CdjStatus$TrackType/CD_DIGITAL_AUDIO "cd"
                        CdjStatus$TrackType/REKORDBOX "rekordbox"
                        CdjStatus$TrackType/UNANALYZED "unanalyzed"
                        "unknown")
        rekordbox (.getRekordboxId status)]
    [source-player source-slot track-type rekordbox]))
