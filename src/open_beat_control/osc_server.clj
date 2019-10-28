(ns open-beat-control.osc-server
  "Manages the OSC server used to offer Beat Link services."
  (:require [overtone.osc :as osc]
            [taoensso.timbre :as timbre]))

(def server
  "The OSC server"
  (atom nil))

(def response-map
  "Keeps track of the port on which to send responses to messages
  coming from particular clients. If none has been specified, the
  default is to respond to a port one higher than the one on which we
  are listening, but this can be configured on a per-client basis
  using the /respond command."
  (atom {}))

(defn respond-handler
  "Handler for the /respond command, which sets the port on which
  responses should be sent to a particular client."
  [msg]
  (let [{:keys [src-host src-port args]} msg]
    (swap! response-map assoc [src-host src-port] (first args))
    #_(timbre/info "Received /respond message:" msg)
    (timbre/info "Will respond to messages from host" src-host "and port" src-port "on port"
                 (get @response-map [src-host src-port]))))

(defn open-server
  "Starts our server listening on the specified port number."
  [port]
  (swap! server (fn [oldval]
                  (when oldval (throw (ex-info "Server already open" {:server oldval})))
                  (osc/osc-server port "Open Beat Control OSC")))
  (osc/osc-handle @server "/respond" respond-handler))
