(ns open-beat-control.util
  "Holds utility functions and values, with few dependencies, so they
  can be easily required by other namespaces."
  (:import [org.deepsymmetry.beatlink DeviceFinder DeviceAnnouncementListener BeatFinder
            VirtualCdj MasterListener DeviceUpdateListener
            CdjStatus CdjStatus$TrackType CdjStatus$TrackSourceSlot]))

(def device-finder
  "Holds the singleton instance of the Device Finder for convenience."
  (DeviceFinder/getInstance))

(def virtual-cdj
  "Holds the singleton instance of the Virtual CDJ for convenience."
  (VirtualCdj/getInstance))

(def beat-finder
  "Holds the singleton instance of the Beat Finder for convenience."
  (BeatFinder/getInstance))

(def ^:private project-version
  (delay (clojure.edn/read-string (slurp (clojure.java.io/resource "open_beat_control/version.edn")))))

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
    (when (clojure.string/starts-with? class-path "jar")
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
