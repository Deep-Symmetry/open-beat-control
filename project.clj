(defproject open-beat-control "0.1.0-SNAPSHOT"
  :description "Provides a subset of beat-link features over Open Sound Control."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v20.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.cli "0.4.2"]
                 [org.deepsymmetry/beat-link "0.5.5"]
                 [overtone/osc-clj "0.9.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [com.fzakaria/slf4j-timbre "0.3.14"]]
  :main ^:skip-aot open-beat-control.core
  :uberjar-name "open-beat-control.jar"
  :manifest {"Name" ~#(str (clojure.string/replace (:group %) "." "/")
                            "/" (:name %) "/")
             "Package" ~#(str (:group %) "." (:name %))
             "Specification-Title" ~#(:name %)
             "Specification-Version" ~#(:version %)}
  :target-path "target/%s"
  :profiles {:dev {:repl-options {:init-ns open-beat-control.core
                                  :welcome (println "open-beat-control loaded.")}
                   :jvm-opts ["-XX:-OmitStackTraceInFastThrow"]}
             :uberjar {:aot :all}})
