(ns oc.notify.config
  "Namespace for the configuration parameters."
  (:require [environ.core :refer (env)]))

(defn- bool
  "Handle the fact that we may have true/false strings, when we want booleans."
  [val]
  (boolean (Boolean/valueOf val)))

;; ----- System -----

(defonce processors (.availableProcessors (Runtime/getRuntime)))
(defonce core-async-limit (+ 42 (* 2 processors)))

(defonce prod? (= "production" (env :env)))
(defonce intro? (not prod?))

;; ----- Sentry -----

(defonce dsn (or (env :open-company-sentry-notify) false))

;; ----- Logging (see https://github.com/ptaoussanis/timbre) -----

(defonce log-level (or (env :log-level) :info))

;; ----- RethinkDB -----

(defonce db-host (or (env :db-host) "localhost"))
(defonce db-port (or (env :db-port) 28015))
(defonce db-name (or (env :db-name) "open_company_auth"))
(defonce db-pool-size (or (env :db-pool-size) (- core-async-limit 21))) ; conservative with the core.async limit

(defonce db-map {:host db-host :port db-port :db db-name})
(defonce db-options (flatten (vec db-map))) ; k/v sequence as clj-rethinkdb wants it

;; ----- DynamoDB -----

(defonce migrations-dir "./src/oc/notify/db/migrations")
(defonce migration-template "./src/oc/notify/assets/migration.template.edn")

(defonce dynamodb-end-point (or (env :dynamodb-end-point) "http://localhost:8000"))

(defonce dynamodb-table-prefix (or (env :dynamodb-table-prefix) "local"))

(defonce dynamodb-opts {
    :access-key (env :aws-access-key-id)
    :secret-key (env :aws-secret-access-key)
    :endpoint dynamodb-end-point
  })

;; ----- HTTP server -----

(defonce hot-reload (bool (or (env :hot-reload) false)))
(defonce notify-server-port (Integer/parseInt (or (env :port) "3010")))
(defonce ensure-origin  (or (env :oc-ws-ensure-origin) true))

;; ----- AWS SQS -----

(defonce aws-access-key-id (env :aws-access-key-id))
(defonce aws-secret-access-key (env :aws-secret-access-key))

(defonce aws-sqs-email-queue (env :aws-sqs-email-queue))
(defonce aws-sqs-bot-queue (env :aws-sqs-bot-queue))
(defonce aws-sqs-notify-queue (env :aws-sqs-notify-queue))

;; ----- Notify service -----

(defonce notification-ttl (or (env :oc-notification-ttl) 30)) ; days

(defonce passphrase (env :open-company-auth-passphrase))