(ns oc.notify.async.persistence
  "
  Persist notifications.

  Use of this persistence is through core/async. A message is sent to the `persistence-chan`.
  "
  (:require [clojure.core.async :as async :refer (>!! <!)]
            [defun.core :refer (defun-)]
            [taoensso.timbre :as timbre]
            [oc.lib.async.watcher :as watcher]
            [oc.notify.resources.notification :as notification]))

;; ----- core.async -----

(defonce persistence-chan (async/chan 10000)) ; buffered channel

(defonce persistence-go (atom true))

;; ----- Event handling -----

(defun- handle-persistence-message
  "
  Handles 2 types of messages: notify, notifications

  NB: Uses 'blocking' core.async put `>!!`, not `parked` core.async put `>!` because even though this
  is called from inside a go block, it's also inside an `async/thread`.
  "

  ;; READS

 ([message :guard :notifications]
  ;; Lookup all notifications for the specified user
  ;; Send the result to the sender's channel as an user/notifications message
  (let [user-id (:user-id message)
        client-id (:client-id message)]
    (timbre/info "Notifications request for:" user-id "by:" client-id)
    (let [notifications {:user-id user-id :notifications (notification/retrieve user-id)}]
      (>!! watcher/sender-chan {:event [:user/notifications notifications]
                                :client-id client-id}))))

  ;; WRITES

  ([message :guard :notify]
  ;; Persist that a user was notified with the specified notification
  (let [notification (:notification message)
        user-id (:user-id notification)
        notify-at (:notify-at message)]
    (timbre/info "Notify request for user:" user-id "at:" notify-at)
    ;; upsert a notification
    (notification/store! notification)
      
    ;; recurse after upserting the message so it seems the client asked for notifications...
    ;; in this way the client will receive an updated user/notifications message for this user
    (handle-persistence-message (-> message
                                  (dissoc :notify)
                                  (assoc :notifications true)
                                  (assoc :user-id user-id)))))

  ([message]
  (timbre/warn "Unknown request in persistence channel" message)))

;; ----- Persistence event loop -----

(defn persistence-loop []
  (reset! persistence-go true)
  (timbre/info "Starting persistence...")
  (async/go (while @persistence-go
    (timbre/debug "Persistence waiting...")
    (let [message (<! persistence-chan)]
      (timbre/debug "Processing message on persistence channel...")
      (if (:stop message)
        (do (reset! persistence-go false) (timbre/info "Persistence stopped."))
        (async/thread
          (try
            (handle-persistence-message message)
          (catch Exception e
            (timbre/error e)))))))))

;; ----- Component start/stop -----

(defn start
  "Start the core.async loop for persisting events."
  []
  (persistence-loop))

(defn stop
  "Stop the the core.async loop persisting events."
  []
  (when @persistence-go
    (timbre/info "Stopping persistence...")
    (>!! persistence-chan {:stop true})))