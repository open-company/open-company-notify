(ns oc.notify.components
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [org.httpkit.server :as httpkit]
            [oc.lib.sentry.core :refer (map->SentryCapturer)]
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
        (assoc component :http-kit nil))
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
        (assoc component :pool nil))
      component)))

(defrecord Handler [handler-fn]
  component/Lifecycle

  (start [component]
    (timbre/info "[handler] started")
    (assoc component :handler (handler-fn component)))

  (stop [component]
    (timbre/info "[handler] stopped")
    (assoc component :handler nil)))

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
        (assoc component :async-consumers nil))
    component)))

(defn notify-system [{:keys [httpkit sqs-consumer sentry]}]
  (component/system-map
    :sentry-capturer (map->SentryCapturer sentry)
    :db-pool (component/using
              (map->RethinkPool {:size c/db-pool-size :regenerate-interval 5})
              [:sentry-capturer])
    :async-consumers (component/using
                        (map->AsyncConsumers {})
                        [:db-pool])
    :sqs-consumer (component/using
                   (sqs/sqs-listener sqs-consumer)
                   [:sentry-capturer])
    :handler (component/using
                (map->Handler {:handler-fn (:handler-fn httpkit)})
                [:sentry-capturer])
    :server  (component/using
                (map->HttpKit {:options {:port (:port httpkit)}})
                [:handler])))