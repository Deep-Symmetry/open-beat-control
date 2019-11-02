(ns open-beat-control.util
  "Holds utility functions and values, with few dependencies, so they
  can be easily required by other namespaces."
  (:import [org.deepsymmetry.beatlink DeviceFinder DeviceAnnouncementListener BeatFinder
            VirtualCdj MasterListener DeviceUpdateListener]))

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
