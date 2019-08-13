(ns oc.notify.async.bot
  "Publish Slack bot triggers to AWS SQS."
  (:require [if-let.core :refer (when-let*)]
            [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.db.common :as db-common]
            [oc.lib.schema :as lib-schema]
            [oc.notify.config :as config]
            [oc.notify.resources.notification :as notification]))

(def BotTrigger 
  "All Slack bot triggers have the following properties."
  {
    :type (schema/enum "notify" "reminder-alert" "reminder-notification" "follow-up")
    :bot {
       :token lib-schema/NonBlankStr
       :id lib-schema/NonBlankStr
       :slack-org-id lib-schema/NonBlankStr
    }
    :receiver {
      :type (schema/enum :user)
      :id lib-schema/NonBlankStr
  }})

(def NotifyTrigger
  (merge BotTrigger {
    :user-id lib-schema/UniqueID
    (schema/optional-key :last-name) schema/Str
    (schema/optional-key :first-name) schema/Str
    (schema/optional-key :name) schema/Str
    (schema/optional-key :avatar-url) (schema/maybe schema/Str)
    (schema/optional-key :teams) (schema/maybe schema/Any)
    (schema/optional-key :timezone) (schema/maybe schema/Str)
    :org {schema/Any schema/Any}
    :status schema/Str
    :notification notification/Notification}))

(defn ->trigger [conn notification org user]
  (when-let* [slack-user (first (vals (:slack-users user)))
              slack-bot (db-common/read-resource conn "slack_orgs" (:slack-org-id slack-user))]
    (merge {
      :type (cond
              (contains? notification :reminder)
              (-> notification :reminder :notification-type)
              (:follow-up? notification)
              "follow-up"
              :else
              "notify")
      :bot {
        :token (:bot-token slack-bot)
        :id (:bot-user-id slack-bot)
        :slack-org-id (:slack-org-id slack-user)
      }
      :receiver {
        :type :user
        :id (:id slack-user)
      }
      :notification notification
      :org (dissoc org :author :authors :viewers :created-at :updated-at :promoted)}
      (select-keys user [:user-id :last-name :first-name :name :avatar-url :teams :timezone :status]))))

(defn send-trigger! [trigger]
  (timbre/info "Bot request to queue:" config/aws-sqs-bot-queue)
  (timbre/trace "Bot request:" trigger)
  (schema/validate NotifyTrigger trigger)
  (timbre/info "Sending request to queue:" config/aws-sqs-bot-queue)
  (sqs/send-message
    {:access-key config/aws-access-key-id
     :secret-key config/aws-secret-access-key}
     config/aws-sqs-bot-queue
     trigger)
  (timbre/info "Request sent to:" config/aws-sqs-bot-queue))