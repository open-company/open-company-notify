(ns oc.notify.async.expo
  (:require [oc.lib.lambda.common :as lambda]
            [oc.lib.user :as user-lib]
            [taoensso.timbre :as timbre]
            [cheshire.core :as json]
            [amazonica.aws.sqs :as sqs]
            [oc.notify.config :as config]))

(defn notification-body
  [notification user]
  (let [reminder? (:reminder? notification)
        follow-up? (:follow-up? notification)
        follow-up-data (when follow-up?
                         (:follow-up notification))
        author (:author notification)
        first-name (or (:first-name author) (first (clojure.string/split (:name author) #"\s")))
        reminder (when reminder?
                   (:reminder notification))
        notification-type (when reminder?
                            (:notification-type reminder))
        reminder-assignee (when reminder?
                            (:assignee reminder))]
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
      (str "Hi " (first (clojure.string/split (:name reminder-assignee) #"\s")) ", it's time to update your team")
      (and (:mention? notification) (:interaction-id notification))
      (str first-name " mentioned you in a comment")
      (:mention? notification)
      (str first-name " mentioned you")
      (:interaction-id notification)
      (str first-name " commented on your post")
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
  (-> (lambda/invoke-fn  "expo-push-notifications-dev-sendPushNotifications"
                         {:notifications push-notifs})
      lambda/parse-response))

;; TODO: extract these hard-coded configuration strings out into config
(defn send-push-notifications!
  [push-notifs]
  (when (seq push-notifs)
    (let [{:keys [tickets]} (lambda-send-push-notifications! push-notifs)]
      (timbre/info "Push notification tickets: " tickets)
      (sqs/send-message
       {:access-key config/aws-access-key-id
        :secret-key config/aws-secret-access-key}
       "carrot-local-dev-calvin-expo"
       (json/generate-string {:notifications push-notifs
                              :tickets tickets}))
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

  (def payload
    (-> (send-push-notifications!
         [{:pushToken "ExponentPushToken[m7WFXDHNuI8PRZPCDXUeVI]"
           :body      "Hey there, this is Clojure!"
           :data      {}}])
        :payload))

  (def parsed-payload
    (json/parse-string
     (.. StandardCharsets/UTF_8 (decode payload) toString)
     keyword))

  (def parsed-body
    (json/parse-string (:body parsed-payload) keyword))

  (String. (.getByets "test") StandardCharsets/UTF_8)

  )
