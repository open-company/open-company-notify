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
(defonce sentry-release (or (env :release) ""))
(defonce sentry-env (or (env :environment) "local"))
(defonce sentry-config {:dsn dsn
                        :release sentry-release
                        :environment sentry-env})

;; ----- Logging (see https://github.com/ptaoussanis/timbre) -----

(defonce log-level (or (env :log-level) :info))

;; ----- RethinkDB -----

(defonce db-host (or (env :db-host) "localhost"))
(defonce db-port (or (env :db-port) 28015))
(defonce auth-db-name (or (env :auth-db-name) "open_company_auth"))
(defonce storage-db-name (or (env :storage-db-name) "open_company_storage"))
(defonce db-pool-size (or (env :db-pool-size) (- core-async-limit 21))) ; conservative with the core.async limit

(defonce auth-db-map {:host db-host :port db-port :db auth-db-name})
(defonce auth-db-options (flatten (vec auth-db-map))) ; k/v sequence as clj-rethinkdb wants it

(defonce storage-db-map {:host db-host :port db-port :db storage-db-name})
(defonce storage-db-options (flatten (vec storage-db-map))) ; k/v sequence as clj-rethinkdb wants it

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
(defonce ensure-origin (bool (or (env :oc-ws-ensure-origin) true)))
(defonce ui-server-url (or (env :ui-server-url) "http://localhost:3559"))

;; ----- AWS SQS -----

(defonce aws-access-key-id (env :aws-access-key-id))
(defonce aws-secret-access-key (env :aws-secret-access-key))

(defonce aws-sqs-email-queue (env :aws-sqs-email-queue))
(defonce aws-sqs-bot-queue (env :aws-sqs-bot-queue))
(defonce aws-sqs-notify-queue (env :aws-sqs-notify-queue))
(defonce aws-sqs-storage-queue (env :aws-sqs-storage-queue))
(defonce aws-sqs-expo-queue (env :aws-sqs-expo-queue))


;; ----- AWS Lambda -----

(defonce aws-lambda-expo-prefix (env :aws-lambda-expo-prefix))

;; ----- Notify service -----

(defonce notification-ttl (or (env :oc-notification-ttl) 30)) ; days

(defonce passphrase (env :open-company-auth-passphrase))
