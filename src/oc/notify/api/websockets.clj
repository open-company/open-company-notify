(ns oc.notify.api.websockets
  "WebSocket server handler."
  (:require [clojure.core.async :as async :refer (>!! <!)]
            [taoensso.sente :as sente]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET POST)]
            [taoensso.sente.server-adapters.http-kit :refer (get-sch-adapter)]
            [oc.lib.jwt :as jwt]
            [oc.lib.async.watcher :as watcher]
            [oc.notify.config :as c]
            [oc.notify.async.persistence :as persistence]))

;; ----- core.async -----

(defonce sender-go (atom true))

;; ----- Sente server setup -----

;; https://github.com/ptaoussanis/sente#on-the-server-clojure-side

(reset! sente/debug-mode?_ (not c/prod?))

(defn check-origin-header [ring-req]
  (let [origin (-> ring-req :headers (get "origin"))]
    (or (= origin "http://localhost:3559")
        (= origin "https://staging.carrot.io")
        (= origin "https://carrot.io"))))

(let [{:keys [ch-recv send-fn connected-uids ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket-server! (get-sch-adapter)
        {:packer :edn
         :user-id-fn (fn [ring-req] (:client-id ring-req)) ; use the client id as the user id
         :csrf-token-fn (fn [ring-req] (:client-id ring-req))
         :handshake-data-fn (fn [ring-req] (timbre/debug "handshake-data-fn") {:carrot :party})})]
  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def connected-uids connected-uids)) ; Read-only atom of uids with Sente WebSocket connections

;; Uncomment to watch the connection atom for changes
; (add-watch connected-uids :connected-uids
;   (fn [_ _ old new]
;     (when (not= old new)
;       (timbre/debug "[websocket]: atom update" new))))

;; ----- Sente incoming event handling -----

(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id) ; Dispatch on event-id

(defn- event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (timbre/trace "[websocket]" event id ?data)
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  ;; Default/fallback case (no other matching handler)
  :default

  [{:keys [event id ?reply-fn]}]
  (timbre/debug "[websocket] unhandled event" event "for" id)
  (when ?reply-fn
    (?reply-fn {:umatched-event-as-echoed-from-from-server event})))

(defmethod -event-msg-handler
  :chsk/handshake

  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (timbre/trace "[websocket] chsk/handshake" event id ?data)
  (when ?reply-fn
    (?reply-fn {:umatched-event-as-echoed-from-from-server event})))

(defmethod -event-msg-handler
  :auth/jwt

  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn ring-req]}]
  (let [origin-check (check-origin-header ring-req)
        client-id (-> ring-req :params :client-id)
        jwt-valid? (jwt/valid? (:jwt ?data) c/passphrase)]
    (timbre/info "[websocket] auth/jwt" (if jwt-valid? "valid" "invalid") "by" client-id)
    ;; Get the jwt and disconnect the client if it's not good!
    (when ?reply-fn
      (?reply-fn {:valid (and origin-check
                              jwt-valid?)}))))

(defmethod -event-msg-handler
  :chsk/ws-ping
  [_]
  (timbre/trace "[websocket] ping"))

(defmethod -event-msg-handler
  :watch/notifications

  [{:as ev-msg :keys [event id ring-req]}]
  (let [user-id (-> ring-req :params :user-id)
        client-id (-> ring-req :params :client-id)]
    (timbre/debug "[websocket] watch/notifications by:" user-id "/" client-id)
    ;; Request a read of existing notifications
    (>!! persistence/persistence-chan {:notifications true :user-id user-id :client-id client-id})
    ;; User watches their own ID so they'll get notified of new notificatians
    (>!! watcher/watcher-chan {:watch true
                               :watch-id user-id
                               :client-id client-id})))

(defmethod -event-msg-handler
  ;; Client disconnected
  :chsk/uidport-close

  [{:as ev-msg :keys [event id ring-req]}]
  (let [user-id (-> ring-req :params :user-id)
        client-id (-> ring-req :params :client-id)]
    (timbre/info "[websocket] container/uidport-close by:" user-id "/" client-id)))


(defmethod -event-msg-handler
  :user/notifications

  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [user-id (-> ring-req :params :user-id)
        client-id (-> ring-req :params :client-id)]
    (timbre/info "[websocket] user/notifications for:" user-id)
    (>!! persistence/persistence-chan {:notifications true :user-id user-id :client-id client-id})))

;; ----- Sente router event loop (incoming from Sente/WebSocket) -----

(defonce router_ (atom nil))

(defn- stop-router! []
  (when-let [stop-fn @router_]
    (stop-fn)))

(defn- start-router! []
  (stop-router!)
  (reset! router_
    (sente/start-server-chsk-router!
      ch-chsk event-msg-handler)))

;; ----- Sender event loop (outgoing to Sente/WebSocket) -----

(defn sender-loop []
  (reset! sender-go true)
  (timbre/info "Starting sender...")
  (async/go (while @sender-go
    (timbre/debug "Sender waiting...")
    (let [message (<! watcher/sender-chan)]
      (timbre/debug "Processing message on sender channel...")
      (if (:stop message)
        (do (reset! sender-go false) (timbre/info "Sender stopped."))
        (async/thread
          (try
            (timbre/info "Sender received:" message)
            (let [event (:event message)
                  client-id (or (:client-id message) (:id message))]
              (timbre/info "[websocket] sending:" (first event) "to:" client-id)
              (chsk-send! client-id event))
            (catch Exception e
              (timbre/error e)))))))))

;; ----- Ring routes -----

(defn routes [sys]
  (compojure/routes
    (GET "/notify-socket/user/:user-id" req (ring-ajax-get-or-ws-handshake req))
    (POST "/notify-socket/user/:user-id" req (ring-ajax-get-or-ws-handshake req))))

;; ----- Component start/stop -----

(defn start
  "Start the incoming WebSocket frame router and the core.async loop for sending outgoing WebSocket frames."
  []
  (start-router!)
  (sender-loop))

(defn stop
  "Stop the incoming WebSocket frame router and the core.async loop for sending outgoing WebSocket frames."
  []
  (timbre/info "Stopping incoming websocket router...")
  (stop-router!)
  (timbre/info "Router stopped.")
  (when @sender-go
    (timbre/info "Stopping sender...")
    (>!! watcher/sender-chan {:stop true})))

;; ----- REPL usage -----

(comment

  ;; WebSocket REPL server
  (require '[oc.notify.components :as components] :reload)
  (require '[oc.notify.app :as app] :reload)
  (require '[oc.notify.api.websockets] :reload)
  (require '[oc.notify.async.persistence] :reload)
  (go)

  ;; WebSocket REPL client
  (require '[http.async.client :as http])
  (require '[oc.notify.resources.notification :as notification])

  (def ws-conn (atom nil))

  (def url "ws://localhost:3010/notify-socket/user/1111-1111-1111?client-id=1")

  (defn on-open [ws]
    (println "Connected to WebSocket."))

  (defn on-close [ws code reason]
    (println "Connection to WebSocket closed.\n"
           (format "[%s] %s" code reason)))

  (defn on-error [ws e]
    (println "ERROR:" e))

  (defn handle-message [ws msg]
    (prn "got message:" msg))

  (defn message-stamp
    "Return a 6 character fragment from a UUID e.g. 51ab4c86"
    []
    (s/join "" (take 2 (rest (s/split (str (java.util.UUID/randomUUID)) #"-")))))

  (defn send-message [msg-type msg-body]
    (println "Sending...")
    (http/send @ws-conn :text (str "+" (pr-str [[msg-type msg-body] (message-stamp)])))
    (println "Sent..."))

  (defn connect-client []
    (future (with-open [client (http/create-client)]
      (let [ws (http/websocket client
                               url
                               :open  on-open
                               :close on-close
                               :error on-error
                               :text handle-message)]
        ; this loop-recur is here as a placeholder to keep the process
        ; from ending, so that the message-handling function will continue to
        ; print messages to STDOUT
        (reset! ws-conn ws)
        (loop []
          (if @ws-conn
            (recur)
            (println "Client stopped!")))))))

  (connect-client)

  ;; Wait until Carrot Party message received back, then
  ;; You should receive a :user/notifications message

  ;; Request another notification message
  (send-message :user/notifications {})

  (reset! ws-conn nil) ; stop the client

)