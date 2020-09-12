(ns open-beat-control.logs
  "Sets up logging."
  (:require [clojure.string :as str]
            [open-beat-control.osc-server :as server]
            [taoensso.timbre.appenders.3rd-party.rotor :as rotor]
            [taoensso.timbre :as timbre]
            [open-beat-control.util :as util]))

(def ^:private osc-appender
  "A timbre appender which sends the log as OSC /log messages to any
  clients that have subscribed to the logging stream."
  {:enabled?   true
   :async?     false
   :min-level  :info
   :rate-limit nil
   :output-fn  :inherit
   :fn         (fn [data]
                 (let [{:keys [output_]} data
                       formatted-output  (force output_)]
                   (server/publish-to-stream "/logging" "/log" formatted-output)))})

(defn- create-appenders
  "Create a set of appenders which rotate the log file at the
  specified path as well as sending to registered OSC clients."
  [path]
  {:rotor (rotor/rotor-appender {:path     path
                                 :max-size 200000
                                 :backlog  5})
   :osc   osc-appender})

(defonce ^{:private true
           :doc "If the user has requested logging to a log directory,
  this will be set to an appropriate set of appenders. Defaults to`,
  logging to stdout and registered OSC clients."}
  appenders (atom {:println (timbre/println-appender {:stream :auto})
                   :osc     osc-appender}))

(defn output-fn
  "Log format (fn [data]) -> string output fn.
  You can modify default options with `(partial output-fn
  <opts-map>)`. This is based on timbre's default, but removes the
  hostname and stack trace fonts."
  ([data] (output-fn nil data))
  ([{:keys [no-stacktrace?] :as opts} data]
   (let [{:keys [level ?err_ msg_ ?ns-str timestamp_ ?line]} data]
     (str @timestamp_ " "
          (str/upper-case (name level))  " "
          "[" (or ?ns-str "?") ":" (or ?line "?") "] - "
          (force msg_)
          (when-not no-stacktrace?
            (when-let [err (force ?err_)]
              (str "\n" (timbre/stacktrace err (assoc opts :stacktrace-fonts {})))))))))

(defn- init-logging-internal
  "Performs the actual initialization of the logging environment,
  protected by the delay below to insure it happens only once."
  []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread e]
       (timbre/error e "Uncaught exception on" (.getName thread)))))
  (timbre/set-config!
   {:level :info  ; #{:trace :debug :info :warn :error :fatal :report}
    :enabled? true

    ;; Control log filtering by namespaces/patterns. Useful for turning off
    ;; logging in noisy libraries, etc.:
    :ns-whitelist  [] #_["my-app.foo-ns"]
    :ns-blacklist  [] #_["taoensso.*"]

    :middleware [] ; (fns [data]) -> ?data, applied left->right

    :timestamp-opts {:pattern "yyyy-MMM-dd HH:mm:ss"
                     :locale :jvm-default
                     :timezone (java.util.TimeZone/getDefault)}

    :output-fn output-fn ; (fn [data]) -> string
    })

  ;; Install the desired log appenders, if they have been configured
  (when-let [custom-appenders @appenders]
    (timbre/merge-config!
     {:appenders custom-appenders}))

  ;; Add the inital log lines that identify build and Java information.
  (timbre/info "Open Beat Control version" (util/get-version) "built" (or (util/get-build-date) "not yet"))
  (timbre/info "Java version" (util/get-java-version))
  (timbre/info "Operating system version" (util/get-os-version)))

(defonce ^{:private true
           :doc "Used to ensure log initialization takes place exactly once."}
  initialized (delay (init-logging-internal)))

(defn init-logging
  "Set up the logging environment for Open Beat Control."
  [log-file]
  ;; Override the default appenders if a log file was specified, then
  ;; resolve the delay, causing initialization to happen if it has
  ;; not yet.
  (when log-file
    (reset! appenders (create-appenders log-file)))
  @initialized)
