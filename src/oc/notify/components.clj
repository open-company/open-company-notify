(ns oc.notify.components
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [org.httpkit.server :as httpkit]
            [oc.lib.sqs :as sqs]
            [oc.lib.async.watcher :as watcher]
            [oc.notify.async.persistence :as persistence]
            [oc.notify.api.websockets :as websockets-api]))
            
(defrecord HttpKit [options handler]
  component/Lifecycle

  (start [component]
    (timbre/info "[http] starting...")
    (let [handler (get-in component [:handler :handler] handler)
          server  (httpkit/run-server handler options)]
      (websockets-api/start)
      (timbre/info "[http] started")
      (assoc component :http-kit server)))

  (stop [{:keys [http-kit] :as component}]
    (if http-kit
      (do
        (timbre/info "[http] stopping...")
        (http-kit)
        (websockets-api/stop)
        (timbre/info "[http] stopped")
        (dissoc component :http-kit))
      component)))

(defrecord Handler [handler-fn]
  component/Lifecycle

  (start [component]
    (timbre/info "[handler] started")
    (assoc component :handler (handler-fn component)))

  (stop [component]
    (timbre/info "[handler] stopped")
    (dissoc component :handler)))

(defrecord AsyncConsumers []
  component/Lifecycle

  (start [component]
    (timbre/info "[async-consumers] starting...")
    (persistence/start) ; core.async channel consumer for persisting events
    (watcher/start) ; core.async channel consumer for watched items (containers watched by websockets) events
    (timbre/info "[async-consumers] started")
    (assoc component :async-consumers true))

  (stop [{:keys [async-consumers] :as component}]
    (if async-consumers
      (do
        (timbre/info "[async-consumers] stopping...")
        (persistence/stop) ; core.async channel consumer for persisting events
        (watcher/stop) ; core.async channel consumer for watched items (containers watched by websockets) events
        (timbre/info "[async-consumers] stopped")
        (dissoc component :async-consumers))
    component)))

(defn notify-system [{:keys [httpkit sqs-consumer]}]
  (component/system-map
    :async-consumers (component/using
                        (map->AsyncConsumers {})
                        [])
    :sqs-consumer (sqs/sqs-listener sqs-consumer)
    :handler (component/using
                (map->Handler {:handler-fn (:handler-fn httpkit)})
                [])
    :server  (component/using
                (map->HttpKit {:options {:port (:port httpkit)}})
                [:handler])))

;; ----- REPL usage -----

(comment

  ;; To use the Interaction Service from the REPL
  (require '[com.stuartsierra.component :as component])
  (require '[oc.notify.config :as config])
  (require '[oc.notify.components :as components] :reload)
  (require '[oc.notify.app :as app] :reload)

  (def notify-service (components/notify-system {:httpkit {:handler-fn app/app
                                                           :port config/notify-server-port}
                                                :sqs-consumer {
                                                  :sqs-queue c/aws-sqs-notify-queue
                                                  :message-handler app/sqs-handler
                                                  :sqs-creds {
                                                    :access-key c/aws-access-key-id
                                                    :secret-key c/aws-secret-access-key}}}))

  (def instance (component/start notify-service))

  ;; if you need to change something, just stop the service

  (component/stop instance)

  ;; reload whatever namespaces you need to and start the service again, change the system if you want by re-def'ing
  ;; the `notify-service` var above.

  (def instance (component/start notify-service))

  )