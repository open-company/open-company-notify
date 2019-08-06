(ns oc.notify.app
  "Namespace for the application which starts all the system components."
  (:gen-class)
  (:require
    [clojure.core.async :as async :refer (>!!)]
    [clojure.walk :as cw]
    [clojure.java.io :as java-io]
    [raven-clj.core :as sentry]
    [raven-clj.interfaces :as sentry-interfaces]
    [raven-clj.ring :as sentry-mw]
    [taoensso.timbre :as timbre]
    [cheshire.core :as json]
    [ring.logger.timbre :refer (wrap-with-logger)]
    [ring.middleware.keyword-params :refer (wrap-keyword-params)]
    [ring.middleware.params :refer (wrap-params)]
    [ring.middleware.reload :refer (wrap-reload)]
    [ring.middleware.cors :refer (wrap-cors)]
    [compojure.core :as compojure :refer (GET)]
    [com.stuartsierra.component :as component]
    [oc.lib.sentry-appender :as sa]
    [oc.lib.schema :as lib-schema]
    [oc.lib.sqs :as sqs]
    [oc.notify.components :as components]
    [oc.notify.config :as c]
    [oc.notify.api.websockets :as websockets-api]
    [oc.notify.async.persistence :as persistence]
    [oc.notify.lib.mention :as mention]
    [oc.notify.resources.notification :as notification]
    [oc.lib.middleware.wrap-ensure-origin :refer (wrap-ensure-origin)]))

(def draft-board-uuid "0000-0000-0000")

;; ----- Unhandled Exceptions -----

;; Send unhandled exceptions to log and Sentry
;; See https://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (timbre/error ex "Uncaught exception on" (.getName thread) (.getMessage ex))
     (when c/dsn
       (sentry/capture c/dsn (-> {:message (.getMessage ex)}
                                 (assoc-in [:extra :exception-data] (ex-data ex))
                                 (sentry-interfaces/stacktrace ex)))))))

;; ----- SQS Incoming Request -----

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
  }
  "
  [msg done-channel]
  (timbre/trace "Received message:" msg)
  (doseq [body (sqs/read-message-body (:body msg))]
    (let [msg-body (or (cw/keywordize-keys (json/parse-string (:Message body))) body)
          change-type (keyword (:notification-type msg-body))
          add? (= change-type :add)
          update? (= change-type :update)
          delete? (= change-type :delete)
          resource-type (keyword (:resource-type msg-body))
          entry? (= resource-type :entry)
          comment? (= resource-type :comment)
          reminder? (= resource-type :reminder)
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
          change-at (or (-> msg-body :content :new :updated-at) ; add / update
                        (:notification-at msg-body)) ; delete
          draft? (or (= board-id draft-board-uuid)
                     (= "draft" (or (-> msg-body :content :new :status)
                                    (and delete? (-> msg-body :content :old :status)))))
          author (lib-schema/author-for-user (:user msg-body))
          new-body (-> msg-body :content :new :body)
          author-id (:user-id author)
          user-id (:user-id author)]
    
      (timbre/info "Received message from SQS:" msg-body)
      ;; On an add/update of entry/comment, look for new mentions
      (when (and
             (not draft?)
             (or add? update?)
             (or entry? comment?))
      
        (timbre/info "Processing change for mentions...")
        (let [old-body (-> msg-body :content :old :body)
              mentions (mention/mention-parents new-body)
              prior-mentions (mention/mention-parents old-body)
              new-mentions (mention/new-mentions prior-mentions mentions)]

          ;; If there are new mentions, we need to persist them
          (when (not-empty new-mentions)
            (timbre/info "Requesting persistence for" (count new-mentions) "mention(s).")
            (doseq [mention new-mentions]
              (if (= (:user-id mention) author-id) ; check for a self-mention
                (timbre/info "Skipping notification creation for self-mention.")
                (let [notification (if comment?
                                     (notification/->InteractionNotification mention org-id board-id entry-id
                                                                             entry-title secure-uuid interaction-id
                                                                             change-at author)
                                     (notification/->InteractionNotification mention org-id board-id entry-id
                                                                             entry-title secure-uuid change-at author))]
                  (>!! persistence/persistence-chan {:notify true
                                                     :org org
                                                     :notification notification})))))))

      ;; On the add of a comment, where the publisher isn't mentioned, notify the publisher (post author)
      (when (and comment? add?)
        (timbre/info "Proccessing comment on a post...")
        (let [publisher (:item-publisher msg-body)
              publisher-id (:user-id publisher)
              mentions (set (map :user-id (mention/new-mentions [] (mention/mention-parents new-body))))
              publisher-mentioned? (mentions publisher-id)
              notify-users (:notify-users msg-body)
              notify-users-without-mentions (filter (fn [user] (not-any? #(= % (:user-id user)) mentions)) notify-users)
              notification (notification/->InteractionNotification publisher new-body org-id board-id entry-id
                                                                   entry-title secure-uuid interaction-id
                                                                   change-at author publisher)]
          (doseq [user notify-users-without-mentions]
            (let [notification (notification/->InteractionNotification publisher new-body org-id board-id entry-id
                                                                             entry-title secure-uuid interaction-id
                                                                             change-at author user)]
                (>!! persistence/persistence-chan {:notify true
                                                   :org org
                                                   :notification notification})))
          (if (= (:user-id publisher) author-id) ; check for a self-comment
            (timbre/info "Skipping notification creation for self-comment.")
            (if publisher-mentioned?
              (timbre/info "Skipping comment notification due to prior mention notification.")
              (>!! persistence/persistence-chan {:notify true
                                               :org org
                                               :notification notification})))))

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
    "Database: " c/db-name "\n"
    "Database pool: " c/db-pool-size "\n"
    "Dynamo DB: " c/dynamodb-end-point "\n"
    "Table prefix: " c/dynamodb-table-prefix "\n"
    "Notification TTL: " c/notification-ttl " days\n"
    "AWS SQS email queue: " c/aws-sqs-email-queue "\n"
    "AWS SQS bot queue: " c/aws-sqs-bot-queue "\n"
    "AWS SQS notify queue: " c/aws-sqs-notify-queue "\n"
    "Hot-reload: " c/hot-reload "\n"
    "Ensure origin: " c/ensure-origin "\n"
    "Sentry: " c/dsn "\n\n"
    (when c/intro? "Ready to serve...\n"))))

;; Ring app definition
(defn app [sys]
  (cond-> (routes sys)
    c/dsn             (sentry-mw/wrap-sentry c/dsn) ; important that this is first
    true              wrap-with-logger
    true              wrap-keyword-params
    true              wrap-params
    true              (wrap-cors #".*")
    c/ensure-origin   wrap-ensure-origin
    c/hot-reload      wrap-reload))

(defn start
  "Start an instance of the service."
  [port]

  ;; Stuff logged at error level goes to Sentry
  (if c/dsn
    (timbre/merge-config!
      {:level (keyword c/log-level)
       :appenders {:sentry (sa/sentry-appender c/dsn)}})
    (timbre/merge-config! {:level (keyword c/log-level)}))

  ;; Start the system
  (-> {:httpkit {:handler-fn app :port port}
       :sqs-consumer {
          :sqs-queue c/aws-sqs-notify-queue
          :message-handler sqs-handler
          :sqs-creds {:access-key c/aws-access-key-id
                      :secret-key c/aws-secret-access-key}}}
    components/notify-system
    component/start)

  ;; Echo config information
  (println (str "\n"
    (when c/intro? (str (slurp (java-io/resource "ascii_art.txt")) "\n"))
    "OpenCompany Notify Service\n"))
  (echo-config port))

(defn -main []
  (start c/notify-server-port))