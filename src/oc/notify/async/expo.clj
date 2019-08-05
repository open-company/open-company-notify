(ns oc.notify.async.expo
  (:require [oc.lib.lambda.common :as lambda]
            [taoensso.timbre :as timbre]))

(defn send-push-notifications!
  [push-notifs]
  (when (seq push-notifs)
    (timbre/info "Sending push notifications" push-notifs)
    (lambda/invoke-fn  "expo-push-notifications-dev-sendPushNotifications"
                       {:notifications push-notifs})))

(defn- notif->body
  [notif]
  "There is new Carrot activity")

(defn- notif->push-notif
  [notif push-token]
  {:pushToken push-token
   :body      (notif->body notif)
   :data      notif})

(defn ->push-notifications
  [notification user]
  (let [push-tokens (:expo-push-tokens user [])]
    (map (partial notif->push-notif notification) push-tokens)))

(comment

  (send-push-notifications!
   [{:pushToken "ExponentPushToken[m7WFXDHNuI8PRZPCDXUeVI]"
     :body "Hey there, this is Clojure!"
     :data {}}])

  )
