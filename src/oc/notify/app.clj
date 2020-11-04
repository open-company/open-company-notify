(ns oc.notify.app
  "Namespace for the application which starts all the system components."
  (:gen-class)
  (:require
    [clojure.core.async :as async :refer (>!!)]
    [clojure.walk :as cw]
    [clojure.java.io :as java-io]
    [oc.lib.sentry.core :as sentry]
    [taoensso.timbre :as timbre]
    [cheshire.core :as json]
    [ring.logger.timbre :refer (wrap-with-logger)]
    [ring.middleware.keyword-params :refer (wrap-keyword-params)]
    [ring.middleware.params :refer (wrap-params)]
    [ring.middleware.reload :refer (wrap-reload)]
    [ring.middleware.cors :refer (wrap-cors)]
    [compojure.core :as compojure :refer (GET)]
    [com.stuartsierra.component :as component]
    [oc.lib.schema :as lib-schema]
    [oc.lib.sqs :as sqs]
    [oc.notify.components :as components]
    [oc.notify.config :as c]
    [oc.notify.api.websockets :as websockets-api]
    [oc.notify.async.persistence :as persistence]
    [oc.notify.async.storage :as storage-notification]
    [oc.notify.lib.mention :as mention]
    [oc.notify.lib.follow-up :as follow-up]
    [oc.notify.resources.notification :as notification]
    [oc.lib.middleware.wrap-ensure-origin :refer (wrap-ensure-origin)]))

(def draft-board-uuid "0000-0000-0000")

;; ----- SQS Incoming Request -----

(defn- user-allowed? [user-id msg-body]
  (let [board-access (:board-access msg-body)
        allowed-users (:allowed-users msg-body)]
    (or (not= board-access "private")
        ((set allowed-users) user-id))))

(defn sqs-handler
  "
  Handle an incoming SQS message to the notify service.

  {
    :notification-type 'add|update|delete'
    :notification-at ISO8601
    :resource-type 'entry|comment'
    :uuid UUID
    :resource-uuid UUID
    :secure-uuid UUID
    :user {...}
    :org {...}
    :board {...}
    :content {:new {...}
              :old {...}}
  }

  {
    :notification-type 'add'
    :resource-type 'reminder'
    :org {...}
    :reminder {...}
    :follow-up {...}
  }
  "
  [msg done-channel]
  (timbre/trace "Received message:" msg)
  (doseq [body (sqs/read-message-body (:body msg))]
    (let [msg-body (or (cw/keywordize-keys (json/parse-string (:Message body))) body)
          change-type (keyword (:notification-type msg-body))
          add? (or (= change-type :add)
                   (= change-type :comment-add))
          update? (= change-type :update)
          delete? (= change-type :delete)
          resource-type (keyword (:resource-type msg-body))
          entry? (= resource-type :entry)
          comment? (= resource-type :comment)
          reminder? (= resource-type :reminder)
          comment-add? (and comment? add?)
          org (:org msg-body)
          org-id (:uuid org)
          board-id (or (-> msg-body :board :uuid)
                       (:board-uuid msg-body))
          entry-key (if comment? :resource-uuid :uuid)
          entry-id (or (-> msg-body :content :new entry-key) ; new or update
                       (-> msg-body :content :old entry-key)) ; delete
          entry-title (-> msg-body :content :new :headline)
          secure-uuid (or (:secure-uuid msg-body) ; interaction
                          (or (-> msg-body :content :new :secure-uuid) ; post new or update
                              (-> msg-body :content :old :secure-uuid))) ; post delete
          interaction-id (when comment? (-> msg-body :content :new :uuid))
          parent-interaction-id (when comment? (-> msg-body :content :new :parent-uuid))
          change-at (or (-> msg-body :content :new :updated-at) ; add / update
                        (:notification-at msg-body)) ; delete
          draft? (or (= board-id draft-board-uuid)
                     (= "draft" (or (-> msg-body :content :new :status)
                                    (and delete? (-> msg-body :content :old :status)))))
          author (lib-schema/author-for-user (:user msg-body))
          new-body (-> msg-body :content :new :body)
          new-abstract (-> msg-body :content :new :abstract)
          author-id (:user-id author)
          user-id (:user-id author)
          comment-author (when (and comment? add?)
                           (-> msg-body :content :new :author))]
      (timbre/info "Received message from SQS:" msg-body)
      ;; On an add/update of an entry, look for new follow-ups
      (when (and
             entry?
             (not draft?)
             (or add? update?))
      
        (timbre/info "Processing change for follow-ups...")
        (let [old-entry (-> msg-body :content :old)
              prior-follow-ups (if (:draft old-entry) [] (:follow-ups old-entry))
              current-follow-ups (-> msg-body :content :new :follow-ups)
              new-follow-ups (follow-up/assigned-follow-ups author prior-follow-ups current-follow-ups)]

          ;; If there are follow-ups on the new entry, we need to create a notification and persist it
          (when (not-empty new-follow-ups)
            (timbre/info "Requesting persistence for" (count new-follow-ups) "follow-up(s).")
            (doseq [follow-up new-follow-ups]
              (let [notification (notification/->FollowUpNotification org-id board-id entry-id entry-title
                                                                      secure-uuid follow-up author)]
                (>!! persistence/persistence-chan {:notify true
                                                   :org org
                                                   :notification notification}))))))

      ;; On an add/update of entry/comment, look for new mentions
      (when (and
             (not draft?)
             (or add? update?)
             (or entry? comment?))
      
        (timbre/info "Processing change for mentions and inbox follows...")
        (let [old-body (-> msg-body :content :old :body)
              old-abstract (-> msg-body :content :old :abstract)
              ;; Mentions will be deduped when calling new-mentions
              prior-mentions (concat (mention/mention-parents old-body) (mention/mention-parents old-abstract))
              mentions (concat (mention/mention-parents new-body) (mention/mention-parents new-abstract))
              new-mentions (mention/new-mentions prior-mentions mentions)
              users-for-follow* (mention/users-from-mentions mentions)
              users-for-follow (if comment-add?
                                 ;; In case of comment add let's add the comment author
                                 ;; to the list that needs to follow the current post
                                 (mapv first (vals
                                  (group-by :user-id (conj users-for-follow* comment-author))))
                                 users-for-follow*)]
          ;; Add follow for all mentioned users (no need to diff from the old, we will override the follow if present)
          (when (not-empty users-for-follow)
            (timbre/info "Requesting follow for" (count users-for-follow) "mention(s)" (if comment-add? " and comment author" ""))
            (storage-notification/send-trigger! (storage-notification/->trigger "follow" entry-id users-for-follow)))
          ;; If there are new mentions, we need to persist them
          (when (not-empty new-mentions)
            (timbre/info "Requesting persistence for" (count new-mentions) "mention(s).")
            (doseq [mention new-mentions
                    :let [self-mention? (= (:user-id mention) author-id)
                          _ (when self-mention?
                              (timbre/info "Skipping notification creation for self-mention."))
                          allowed-user? (user-allowed? (:user-id mention) msg-body)
                          _ (when-not allowed-user?
                              (timbre/info "Skipping notification creation for mention since user is not allowed in the board anymore."))
                          notification (if comment?
                                         (notification/->InteractionNotification mention org-id board-id entry-id
                                                                                 entry-title secure-uuid interaction-id
                                                                                 parent-interaction-id change-at author)
                                         (notification/->InteractionNotification mention org-id board-id entry-id
                                                                                 entry-title secure-uuid change-at author))]
                    :when (and (not self-mention?)
                               allowed-user?)]
              (>!! persistence/persistence-chan {:notify true
                                                 :org org
                                                 :notification notification})))))

      ;; On the add of a comment, where the user(s) to be notified (post author and authors of prior comments)
      ;; aren't also mentioned (and therefore already notified), notify them
      (when (and comment? add?)
        (timbre/info "Proccessing a new comment...")
        (let [publisher (:item-publisher msg-body)
              publisher-id (:user-id publisher)
              mentions (set (map :user-id (mention/new-mentions [] (mention/mention-parents new-body))))
              publisher-mentioned? (mentions publisher-id)
              notify-users (:notify-users msg-body)
              notify-users-without-mentions (filter (fn [user] (not-any? #(= % (:user-id user)) mentions)) notify-users)
              notification (notification/->InteractionNotification publisher new-body org-id board-id entry-id
                                                                   entry-title secure-uuid interaction-id
                                                                   parent-interaction-id change-at author publisher)]
          ;; Send a comment-add notification to storage to alert the clients to refresh thir inbox
          (timbre/info "Sending comment-add notification to Storage for" entry-id)
          (storage-notification/send-trigger! (storage-notification/->trigger "comment-add" entry-id [comment-author]))
          (doseq [user notify-users-without-mentions
                  :let [notification (notification/->InteractionNotification publisher new-body org-id board-id entry-id
                                                                             entry-title secure-uuid interaction-id
                                                                             parent-interaction-id change-at author user)]
                  :when (user-allowed? (:user-id user) msg-body)]
            (>!! persistence/persistence-chan {:notify true
                                               :org org
                                               :notification notification}))
          (cond (= (:user-id publisher) author-id) ; check for a self-comment
                (timbre/info "Skipping notification creation for self-comment.")
                publisher-mentioned?
                (timbre/info "Skipping comment notification due to prior mention notification.")
                (not (user-allowed? (:user-id publisher) msg-body))
                (timbre/info "Skipping comment notification due to user not allowed in board anymore.")
                :else
                (>!! persistence/persistence-chan {:notify true
                                                   :org org
                                                   :notification notification}))))

      ;; On the add of a new reminder, create a notification and persist it
      (when (and reminder? add?)
        (timbre/info "Proccessing new reminder...")
        (let [reminder (:reminder msg-body)
              notification (notification/->ReminderNotification org-id reminder)]
          (>!! persistence/persistence-chan {:notify true
                                             :org org
                                             :notification notification})))

      ;; Draft, org, board, or unknown
      (cond
       draft?
       (timbre/trace "Skipping draft message from SQS:" change-type resource-type)

       (or comment? entry? (and reminder? add?))
       true ; already handled

       (= resource-type :org)
       (timbre/trace "Unhandled org message from SQS:" change-type resource-type)

       (= resource-type :board)
       (timbre/trace "Unhandled board message from SQS:" change-type resource-type)

       :else
       (timbre/warn "Unknown message from SQS:" change-type resource-type))))

  (sqs/ack done-channel msg))

;; ----- Request Routing -----

(defn routes [sys]
  (compojure/routes
    (GET "/ping" [] {:body "OpenCompany Notify Service: OK" :status 200}) ; Up-time monitor
    (GET "/---error-test---" [] (/ 1 0))
    (GET "/---500-test---" [] {:body "Testing bad things." :status 500})
    (websockets-api/routes sys)))

;; ----- System Startup -----

(defn echo-config [port]
  (println (str "\n"
    "Running on port: " port "\n"
    "Auth database: " c/auth-db-name "\n"
    "Storage database: " c/storage-db-name "\n"
    "Database pool: " c/db-pool-size "\n"
    "Dynamo DB: " c/dynamodb-end-point "\n"
    "Table prefix: " c/dynamodb-table-prefix "\n"
    "Notification TTL: " c/notification-ttl " days\n"
    "AWS SQS email queue: " c/aws-sqs-email-queue "\n"
    "AWS SQS bot queue: " c/aws-sqs-bot-queue "\n"
    "AWS SQS notify queue: " c/aws-sqs-notify-queue "\n"
    "AWS SQS storage queue: " c/aws-sqs-storage-queue "\n"
    "Hot-reload: " c/hot-reload "\n"
    "Ensure origin: " c/ensure-origin "\n"
    "UI endpoint: " c/ui-server-url "\n"
    "Sentry: " c/dsn "\n"
    "  env: " c/sentry-env "\n"
    (when-not (clojure.string/blank? c/sentry-release)
      (str "  release: " c/sentry-release "\n"))
    "\n"
    (when c/intro? "Ready to serve...\n"))))

;; Ring app definition
(defn app [sys]
  (cond-> (routes sys)
    ; important that this is first
    c/dsn             (sentry/wrap c/sentry-config)
    true              wrap-with-logger
    true              wrap-keyword-params
    true              wrap-params
    true              (wrap-cors #".*")
    c/ensure-origin   (wrap-ensure-origin c/ui-server-url)
    c/hot-reload      wrap-reload))

(defn start
  "Start an instance of the service."
  [port]

  ;; Stuff logged at error level goes to Sentry
  (if c/dsn
    (timbre/merge-config!
      {:level (keyword c/log-level)
       :appenders {:sentry (sentry/sentry-appender c/sentry-config)}})
    (timbre/merge-config! {:level (keyword c/log-level)}))

  ;; Start the system
  (-> {:httpkit {:handler-fn app :port port}
       :sentry c/sentry-config
       :sqs-consumer {
          :sqs-queue c/aws-sqs-notify-queue
          :message-handler sqs-handler
          :sqs-creds {:access-key c/aws-access-key-id
                      :secret-key c/aws-secret-access-key}}}
    components/notify-system
    component/start)

  ;; Echo config information
  (println (str "\n"
    (when c/intro? (str (slurp (java-io/resource "oc/assets/ascii_art.txt")) "\n"))
    "OpenCompany Notify Service\n"))
  (echo-config port))

(defn -main []
  (start c/notify-server-port))