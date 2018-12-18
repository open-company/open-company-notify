(ns oc.notify.resources.notification
  "Store notification details with a TTL"
  (:require [taoensso.faraday :as far]
            [schema.core :as schema]
            [clj-time.core :as time]
            [clj-time.coerce :as coerce]
            [oc.lib.schema :as lib-schema]
            [oc.notify.config :as c]
            [oc.lib.dynamo.common :as ttl]))

;; ----- DynamoDB -----

(def table-name (keyword (str c/dynamodb-table-prefix "_notification")))

;; ----- Schema -----

(def Mention {
  :user-id lib-schema/UniqueID
  :mention {schema/Keyword schema/Any}
  :parent lib-schema/NonBlankStr})

(def Notification {
  :user-id lib-schema/UniqueID
  :org-id lib-schema/UniqueID
  :board-id lib-schema/UniqueID
  :entry-id lib-schema/UniqueID
  (schema/optional-key :entry-title) schema/Str
  :secure-uuid lib-schema/UniqueID
  (schema/optional-key :interaction-id) lib-schema/UniqueID
  :notify-at lib-schema/ISO8601
  :content lib-schema/NonBlankStr
  :mention schema/Bool
  :author lib-schema/Author})

;; ----- Constructor -----

(schema/defn ^:always-validate ->Notification :- Notification
  
  ;; arity 7: a mention in a post
  ([mention :- Mention
   org-id :- lib-schema/UniqueID
   board-id :- lib-schema/UniqueID
   entry-id :- lib-schema/UniqueID
   entry-title
   secure-uuid :- lib-schema/UniqueID
   change-at :- lib-schema/ISO8601
   author :- lib-schema/Author]
   {:user-id (:user-id mention)
    :org-id org-id
    :board-id board-id
    :entry-id entry-id
    :entry-title (if (nil? entry-title) "comment" entry-title)
    :secure-uuid secure-uuid
    :notify-at change-at
    :content (:parent mention)
    :mention true
    :author author})

  ;; arity 8: a mention in a comment
  ([mention org-id board-id entry-id entry-title secure-id interaction-id :- lib-schema/UniqueID change-at author]
     (assoc (->Notification mention org-id board-id entry-id entry-title secure-id change-at author)
    :interaction-id interaction-id))

  ;; arity 9: a comment on a post
  ([entry-publisher :- lib-schema/Author
   comment-body :- schema/Str
   org-id :- lib-schema/UniqueID
   board-id :- lib-schema/UniqueID
   entry-id :- lib-schema/UniqueID
   entry-title
   secure-uuid :- lib-schema/UniqueID
   interaction-id :- lib-schema/UniqueID
   change-at :- lib-schema/ISO8601
   author :- lib-schema/Author]
   {:user-id (:user-id entry-publisher)
    :org-id org-id
    :board-id board-id
    :entry-id entry-id
    :entry-title (if (nil? entry-title) "comment" entry-title)
    :secure-uuid secure-uuid
    :interaction-id interaction-id
    :notify-at change-at
    :content comment-body
    :mention false
    :author author}))



;; ----- DB Operations -----

(schema/defn ^:always-validate store!
  [notification :- Notification]
  (far/put-item c/dynamodb-opts table-name
    (assoc
      (clojure.set/rename-keys notification {
        :user-id :user_id
        :board-id :board_id
        :entry-id :entry_id
        :secure-uuid :secure_uuid
        :interaction-id :interaction_id
        :notify-at :notify_at})
      :ttl (ttl/ttl-epoch c/notification-ttl)
      ))
  true)

(schema/defn ^:always-validate retrieve :- [Notification]
  [user-id :- lib-schema/UniqueID]
  (->> (far/query c/dynamodb-opts table-name {:user_id [:eq user-id]}
        {:filter-expr "#k > :v"
         :expr-attr-names {"#k" "ttl"}
         :expr-attr-vals {":v" (ttl/ttl-now)}})
      (map #(clojure.set/rename-keys % {
        :user_id :user-id
        :board_id :board-id
        :entry_id :entry-id
        :secure_uuid :secure-uuid
        :interaction_id :interaction-id
        :notify_at :notify-at}))
      (map #(dissoc % :ttl))))

(defn create-table
  ([] (create-table c/dynamodb-opts))
  
  ([dynamodb-opts]
  (far/ensure-table dynamodb-opts table-name
    [:user_id :s]
    {:range-keydef [:notify_at :s]
     :throughput {:read 1 :write 1}
     :block? true})))

(defn delete-table
  ([] (delete-table c/dynamodb-opts))
  
  ([dynamodb-opts]
  (far/delete-table dynamodb-opts table-name)))

(defn delete-all! []
  (delete-table)
  (create-table))

(comment

  (require '[oc.lib.time :as oc-time])
  (require '[oc.notify.resources.notification :as notification] :reload)
  (require '[oc.notify.lib.mention :as mention] :reload)

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

  (def mention1 (first (mention/new-mentions [] [(first (mention/mention-parents
    "<p>I'm not sure <span
      class='medium-editor-mention-at oc-mention'
      data-first-name='Albert'
      data-last-name='Camus'
      data-user-id='1111-1111-1111'
      data-email='camus@combat.org'
      data-avatar-url='...'
      data-found='true'>@Albert Camus</span>, what do you think about this?"))])))

  (def n1 (notification/->Notification mention1 "2222-2222-2222" "3333-3333-3333" "4444-4444-4444" (oc-time/current-timestamp) coyote))
  (notification/store! n1)
  (notification/retrieve "1111-1111-1111")

)