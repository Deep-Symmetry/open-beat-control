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
