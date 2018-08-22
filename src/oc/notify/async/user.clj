(ns oc.notify.async.user
  "
  Read users from Auth DB (read-only).

  Use of this is through core/async. A message is sent to the `user-chan`.
  "
  (:require [clojure.core.async :as async :refer (>!! <!)]
            [defun.core :refer (defun-)]
            [taoensso.timbre :as timbre]))

;; ----- core.async -----

(defonce user-chan (async/chan 10000)) ; buffered channel

(defonce user-go (atom true))

;; ----- Event handling -----

(defun- handle-user-message
 ;  "
 ;  Handles 2 types of messages: notify, notifications

 ;  NB: Uses 'blocking' core.async put `>!!`, not `parked` core.async put `>!` because even though this
 ;  is called from inside a go block, it's also inside an `async/thread`.
 ;  "

 ;  ;; READS

 ; ([message :guard :notifications]
 ;  ;; Lookup all notifications for the specified user
 ;  ;; Send the result to the sender's channel as an user/notifications message
 ;  (let [user-id (:user-id message)
 ;        client-id (:client-id message)]
 ;    (timbre/info "Notifications request for:" user-id "by:" client-id)
 ;    (let [notifications {:user-id user-id :notifications (notification/retrieve user-id)}]
 ;      (>!! watcher/sender-chan {:event [:user/notifications notifications]
 ;                                :client-id client-id}))))

 ;  ;; WRITES

 ;  ([message :guard :notify]
 ;  ;; Persist that a user was notified with the specified notification
 ;  (let [notification (:notification message)
 ;        user-id (:user-id notification)
 ;        notify-at (:notify-at notification)]
 ;    (timbre/info "Storing notification for user:" user-id "at:" notify-at)
 ;    ;; upsert a notification
 ;    (notification/store! notification)
 ;    ;; notify any watching client of the notification
 ;    (>!! watcher/watcher-chan {:send true
 ;                               :watch-id user-id
 ;                               :event :user/notification
 ;                               :payload notification})))

  ([_db-pool message]
  (timbre/warn "Unknown request in user channel" message)))

;; ----- User event loop -----

(defn user-loop [db-pool]
  (reset! user-go true)
  (timbre/info "Starting user...")
  (async/go (while @user-go
    (timbre/debug "User waiting...")
    (let [message (<! user-chan)]
      (timbre/debug "User message on uner channel...")
      (if (:stop message)
        (do (reset! user-go false) (timbre/info "User stopped."))
        (async/thread
          (try
            (handle-user-message db-pool message)
          (catch Exception e
            (timbre/error e)))))))))

;; ----- Component start/stop -----

(defn start
  "Start the core.async loop for user events."
  [db-pool]
  (user-loop db-pool))

(defn stop
  "Stop the the core.async loop for user events."
  []
  (when @user-go
    (timbre/info "Stopping user...")
    (>!! user-chan {:stop true})))