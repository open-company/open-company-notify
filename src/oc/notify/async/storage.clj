(ns oc.notify.async.storage
  "Publish Slack bot triggers to AWS SQS."
  (:require [if-let.core :refer (when-let*)]
            [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.notify.config :as config]))

(def StorageTrigger
  "All Storage triggers have the following properties."
  {
    :type (schema/enum "inbox-action")
    :sub-type (schema/enum "follow" "unfollow" "dismiss")
    :item-id lib-schema/UniqueID
    :users [lib-schema/User]
    (schema/optional-key :dismiss-at) lib-schema/ISO8601
  })

(defn ->trigger [subtype entry-id users]
  {:type "inbox-action"
   :sub-type subtype
   :users users
   :item-id entry-id})

(defn send-trigger! [trigger]
  (timbre/info "Storage request to queue:" config/aws-sqs-storage-queue)
  (timbre/trace "Storage request:" trigger)
  (schema/validate StorageTrigger trigger)
  (timbre/info "Sending request to queue:" config/aws-sqs-storage-queue)
  (sqs/send-message
    {:access-key config/aws-access-key-id
     :secret-key config/aws-secret-access-key}
     config/aws-sqs-storage-queue
     trigger)
  (timbre/info "Request sent to:" config/aws-sqs-storage-queue))