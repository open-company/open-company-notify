(ns oc.notify.async.expo
  (:require [oc.lib.lambda.common :as lambda]
            [oc.lib.user :as user-lib]
            [taoensso.timbre :as timbre]))

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

(defn- ->push-notification
  [notification user push-token]
  (when-let [body (notification-body notification user)]
    {:pushToken push-token
     :body      body
     :data      notification}))

(defn ->push-notifications
  [notification user]
  (let [push-tokens (:expo-push-tokens user [])]
    (keep (partial ->push-notification notification user) push-tokens)))

(defn send-push-notifications!
  [push-notifs]
  (when (seq push-notifs)
    (timbre/info "Sending push notifications" push-notifs)
    ;; TODO: extract this function value from config
    (lambda/invoke-fn  "expo-push-notifications-dev-sendPushNotifications"
                       {:notifications push-notifs})))

(comment

  (send-push-notifications!
   [{:pushToken "ExponentPushToken[m7WFXDHNuI8PRZPCDXUeVI]"
     :body "Hey there, this is Clojure!"
     :data {}}])

  )
