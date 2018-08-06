(ns oc.notify.resources.notification
  "Store notification details with a TTL"
  (:require [taoensso.faraday :as far]
            [schema.core :as schema]
            [clj-time.core :as time]
            [clj-time.coerce :as coerce]
            [oc.lib.schema :as lib-schema]
            [oc.lib.time :as oc-time]
            [oc.notify.config :as c]))

;; ----- DynamoDB -----

(def table-name (keyword (str c/dynamodb-table-prefix "_notification")))

;; ----- Schema -----

(def Notification {
  :user-id lib-schema/UniqueID
  :board-id lib-schema/UniqueID
  :entry-id lib-schema/UniqueID
  :interaction-id lib-schema/UniqueID
  :notify-at lib-schema/ISO8601
  :content lib-schema/NonBlankStr
  :mention schema/Bool
  :author lib-schema/Author})

;; ----- Constructor -----

(schema/defn ^:always-validate ->Notification :- Notification
  [user-id :- lib-schema/UniqueID board-id :- lib-schema/UniqueID entry-id :- lib-schema/UniqueID
   interaction-id :- lib-schema/UniqueID content :- lib-schema/NonBlankStr author :- lib-schema/Author]
   {:user-id user-id
    :board-id board-id
    :entry-id entry-id
    :interaction-id interaction-id
    :notify-at (oc-time/current-timestamp)
    :content content
    :mention false
    :author author})

;; ----- DB Operations -----

(schema/defn ^:always-validate store!
  [notification :- Notification]
  (far/put-item c/dynamodb-opts table-name
    (assoc
      (clojure.set/rename-keys notification {
        :user-id :user_id
        :board-id :board_id
        :entry-id :entry_id
        :interaction-id :interaction_id
        :notify-at :notify_at})
      :ttl (coerce/to-long (time/plus (time/now) (time/days c/notification-ttl)))))
  true)

(schema/defn ^:always-validate retrieve :- [Notification]
  [user-id :- lib-schema/UniqueID]
  (->> (far/query c/dynamodb-opts table-name {:user_id [:eq user-id]})
      (map #(clojure.set/rename-keys % {
        :user_id :user-id
        :board_id :board-id
        :entry_id :entry-id
        :interaction_id :interaction-id
        :notify_at :notify-at}))
      (map #(dissoc % :ttl))))

(comment

  (require '[oc.notify.resources.notification :as notification] :reload)

  (far/list-tables c/dynamodb-opts)

  (far/delete-table c/dynamodb-opts notification/table-name)
  (aprint
    (far/create-table c/dynamodb-opts
      notification/table-name
      [:user_id :s]
      {:range-keydef [:notify_at :s]
       :throughput {:read 1 :write 1}
       :block? true}))

  (aprint (far/describe-table c/dynamodb-opts notification/table-name))

  (def coyote {
    :user-id "1234-5678-1234"
    :name "Wile E. Coyote"
    :avatar-url "http://www.emoticonswallpapers.com/avatar/cartoons/Wiley-Coyote-Dazed.jpg"})

  (def reply (notification/->Notification "1111-1111-1111" "2222-2222-2222" "3333-3333-3333" "4444-4444-4444"
    "Reply to me." coyote))
  (notification/store! reply)
  (notification/retrieve "1111-1111-1111")

  (def mention (notification/->Notification "1111-1111-1111" "2222-2222-2222" "3333-3333-3333" "5555-5555-5555"
    "Mention @me." coyote))
  (notification/store! mention)
  (notification/retrieve "1111-1111-1111")

)