(ns oc.notify.components
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [org.httpkit.server :as httpkit]
            [oc.lib.db.pool :as pool]
            [oc.lib.sqs :as sqs]
            [oc.lib.async.watcher :as watcher]
            [oc.notify.async.persistence :as persistence]
            [oc.notify.async.user :as user]
            [oc.notify.api.websockets :as websockets-api]
            [oc.notify.config :as c]))
            
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

(defrecord RethinkPool [size regenerate-interval pool]
  component/Lifecycle
  (start [component]
    (timbre/info "[rehinkdb-pool] starting")
    (let [pool (pool/fixed-pool (partial pool/init-conn c/db-options) pool/close-conn
                                {:size size :regenerate-interval regenerate-interval})]
      (timbre/info "[rehinkdb-pool] started")
      (assoc component :pool pool)))
  (stop [component]
    (if pool
      (do
        (pool/shutdown-pool! pool)
        (dissoc component :pool))
      component)))

(defrecord Handler [handler-fn]
  component/Lifecycle

  (start [component]
    (timbre/info "[handler] started")
    (assoc component :handler (handler-fn component)))

  (stop [component]
    (timbre/info "[handler] stopped")
    (dissoc component :handler)))

(defrecord AsyncConsumers [db-pool]
  component/Lifecycle

  (start [component]
    (timbre/info "[async-consumers] starting...")
    (persistence/start) ; core.async channel consumer for persisting events
    (user/start (:pool db-pool)) ; core.async channel consumer for looking up users 
    (watcher/start) ; core.async channel consumer for watched items (containers watched by websockets) events
    (timbre/info "[async-consumers] started")
    (assoc component :async-consumers true))

  (stop [{:keys [async-consumers] :as component}]
    (if async-consumers
      (do
        (timbre/info "[async-consumers] stopping...")
        (persistence/stop) ; core.async channel consumer for persisting events
        (user/stop) ; core.async channel consumer for looking up users
        (watcher/stop) ; core.async channel consumer for watched items (containers watched by websockets) events
        (timbre/info "[async-consumers] stopped")
        (dissoc component :async-consumers))
    component)))

(defn notify-system [{:keys [httpkit sqs-consumer]}]
  (component/system-map
    :db-pool (map->RethinkPool {:size c/db-pool-size :regenerate-interval 5})
    :async-consumers (component/using
                        (map->AsyncConsumers {})
                        [:db-pool])
    :sqs-consumer (sqs/sqs-listener sqs-consumer)
    :handler (component/using
                (map->Handler {:handler-fn (:handler-fn httpkit)})
                [])
    :server  (component/using
                (map->HttpKit {:options {:port (:port httpkit)}})
                [:handler])))