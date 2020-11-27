(ns oc.notify.async.email
  "Publish email triggers to AWS SQS."
  (:require [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.notify.config :as config]
            [oc.notify.resources.notification :as notification]))

(def EmailTrigger
  {:type (schema/enum "notify" "reminder-alert" "reminder-notification" "follow-up")
   :user-id lib-schema/UniqueID
   :to lib-schema/EmailAddress
   (schema/optional-key :last-name) schema/Str
   (schema/optional-key :first-name) schema/Str
   (schema/optional-key :name) schema/Str
   (schema/optional-key :avatar-url) (schema/maybe schema/Str)
   (schema/optional-key :teams) (schema/maybe schema/Any)
   (schema/optional-key :timezone) (schema/maybe schema/Str)
   :org {schema/Any schema/Any}
   :status schema/Str
   :notification notification/Notification})

(defn ->trigger [notification org user]
  (merge {
    :type (cond
            (contains? notification :reminder)
            (-> notification :reminder :notification-type)
            (:team? notification)
            "team"
            (:follow-up? notification)
            "follow-up"
            :else
            "notify")
    :to (:email user)
    :notification notification
    :org (dissoc org :author :authors :viewers :created-at :updated-at :promoted)}
    (select-keys user [:user-id :last-name :first-name :name :avatar-url :teams :timezone :status])))

(defn send-trigger! [trigger]
  (timbre/info "Email request to queue:" config/aws-sqs-email-queue)
  (timbre/trace "Email request:" trigger)
  (schema/validate EmailTrigger trigger)
  (timbre/info "Sending request to queue:" config/aws-sqs-email-queue)
  (sqs/send-message
    {:access-key config/aws-access-key-id
     :secret-key config/aws-secret-access-key}
     config/aws-sqs-email-queue
     trigger)
  (timbre/info "Request sent to:" config/aws-sqs-email-queue))