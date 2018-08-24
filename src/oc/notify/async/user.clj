(ns oc.notify.async.user
  "
  Read users from Auth DB (read-only).

  Use of this is through core/async. A message is sent to the `user-chan`.
  "
  (:require [clojure.core.async :as async :refer (>!! <!)]
            [if-let.core :refer (if-let*)]
            [defun.core :refer (defun-)]
            [taoensso.timbre :as timbre]
            [oc.lib.db.pool :as pool]
            [oc.lib.db.common :as db-common]))

;; ----- core.async -----

(defonce user-chan (async/chan 10000)) ; buffered channel

(defonce user-go (atom true))

;; ----- Event handling -----

(defun- handle-user-message
  "
  Handles 1 types of message: notify

  NB: Uses 'blocking' core.async put `>!!`, not `parked` core.async put `>!` because even though this
  is called from inside a go block, it's also inside an `async/thread`.
  "

  ([db-pool message :guard :notify]
  (pool/with-pool [conn db-pool]
    (if-let* [user-id (:user-id message)
              notification (:notification message)
              notify-user (db-common/read-resource conn "users" user-id)]
      (timbre/info "Handle user message for:" user-id))))

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