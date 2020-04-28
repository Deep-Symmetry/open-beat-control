(defproject open-beat-control :lein-v
  :description "Provides a subset of beat-link features over Open Sound Control."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v20.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.cli "1.0.194"]
                 [org.deepsymmetry/beat-link "0.6.2-SNAPSHOT"]
                 [beat-carabiner "0.2.1"]
                 [overtone/osc-clj "0.9.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [com.fzakaria/slf4j-timbre "0.3.19"]]
  :repositories {"sonatype-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"}

  :profiles {:dev     {:repl-options {:init-ns open-beat-control.core
                                      :welcome (println "open-beat-control loaded.")}
                       :jvm-opts     ["-XX:-OmitStackTraceInFastThrow" "-Dapple.awt.UIElement=true"]}
             :uberjar {:aot :all}}

  :main ^:skip-aot open-beat-control.core
  :uberjar-name "open-beat-control.jar"

  :manifest {"Name"                  ~#(str (clojure.string/replace (:group %) "." "/")
                                            "/" (:name %) "/")
             "Package"               ~#(str (:group %) "." (:name %))
             "Specification-Title"   ~#(:name %)
             "Specification-Version" ~#(:version %)
             "Build-Timestamp"       ~(str (java.util.Date.))}

  :plugins [[com.roomkey/lein-v "7.1.0"]]

  :middleware [lein-v.plugin/middleware]

  ;; Perform the task which sets up the resource allowing runtime
  ;; access to the build version information.
  :prep-tasks ["compile"
               ["v" "cache" "resources/open_beat_control" "edn"]]

  ;; Miscellaneous sanitary settings
  :pedantic :warn
  :min-lein-version "2.0.0")
