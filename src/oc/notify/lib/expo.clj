(ns oc.notify.lib.expo
  (:require [amazonica.aws.sqs :as sqs]
            [cheshire.core :as json]
            [clojure.string :as cstr]
            [oc.lib.lambda.common :as lambda]
            [oc.lib.user :as user-lib]
            [oc.notify.config :as config]
            [taoensso.timbre :as timbre]
            [oc.lib.html :as lib-html]))

(def ^:private max-content-length 100)

(defn- summarize-content
  [notification]
  (let [summarize #(cstr/join (take max-content-length %))]
    (some-> notification
            :content
            (lib-html/strip-html-tags :decode-entities? true)
            cstr/trim
            summarize)))

(defn notification-body
  [notification user]
  (let [reminder?         (:reminder? notification)
        follow-up?        (:follow-up? notification)
        follow-up-data    (when follow-up?
                            (:follow-up notification))
        author            (:author notification)
        first-name        (or (:first-name author) (first (cstr/split (:name author) #"\s")))
        reminder          (when reminder?
                            (:reminder notification))
        notification-type (when reminder?
                            (:notification-type reminder))
        reminder-assignee (when reminder?
                            (:assignee reminder))
        extra-content     (summarize-content notification)]
    (cond
      (and follow-up?
           follow-up-data
           (not= (-> follow-up-data :author :user-id) (:user-id user))
           (not (:completed? follow-up-data)))
      (str (user-lib/name-for (:author follow-up-data)) " created a follow-up for you")
      (and reminder
           (= notification-type "reminder-notification"))
      (str first-name " created a new reminder for you")
      (and reminder
           (= notification-type "reminder-alert"))
      (str "Hi " (first (cstr/split (:name reminder-assignee) #"\s")) ", it's time to update your team")
      (and (:mention? notification) (:interaction-id notification))
      (str first-name " mentioned you in a comment:\n" extra-content)
      (:mention? notification)
      (str first-name " mentioned you:\n" extra-content)
      (:interaction-id notification)
      (str first-name " commented on your post:\n" extra-content)
      :else
      nil)))

(defn ->push-notification
  [notification user push-token]
  (when-let [body (notification-body notification user)]
    {:pushToken push-token
     :body      body
     :data      notification}))

(defn ->push-notifications
  [notification user]
  (let [push-tokens (:expo-push-tokens user [])]
    (keep (partial ->push-notification notification user) push-tokens)))

(defn- lambda-send-push-notifications!
  [push-notifs]
  (timbre/info "Sending push notifications: " push-notifs)
  (lambda/parse-response
   (lambda/invoke-fn (str config/aws-lambda-expo-prefix "sendPushNotifications")
                     {:notifications push-notifs})))

(defn send-push-notifications!
  [push-notifs]
  (when (seq push-notifs)
    (let [{:keys [tickets error]} (lambda-send-push-notifications! push-notifs)]
      (if error
        (throw (ex-info error {:push-notifications push-notifs}))
        (sqs/send-message
         {:access-key config/aws-access-key-id
          :secret-key config/aws-secret-access-key}
         config/aws-sqs-expo-queue
         (json/generate-string {:push-notifications push-notifs
                                :tickets tickets})))
      tickets)))

(comment

  (send-push-notifications!
   [{:pushToken "ExponentPushToken[m7WFXDHNuI8PRZPCDXUeVI]"
     :body      "Hey there, this is Clojure!"
     :data      {}}])

  (send-push-notifications!
   [{:pushToken "ExponentPushToken[ns_q4cLNLBO0KqnY9YvUBa]"
     :body      "Hey there, this is Clojure!"
     :data      {}}])

  (send-push-notifications!
   [{:pushToken "NOPE"
     :body      "Hey there, this is Clojure!"
     :data      {}}])

  )
