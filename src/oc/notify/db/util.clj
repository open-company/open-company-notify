(ns oc.notify.db.util
  (:require [taoensso.faraday :as far]
            [clj-time.coerce :as coerce]
            [oc.notify.config :as config]
            [oc.notify.resources.notification :as notification]))

(defn fix-notification-ttl []
  (println "Scanning " notification/table-name)
  (let [idx (atom 0)
        results (far/scan config/dynamodb-opts notification/table-name {:attr-conds {:ttl [:gt 9999999999]}})]
    (for [r results]
      (let [fixed-ttl (coerce/to-epoch (coerce/from-long (long (:ttl r))))]
        (println "   " @idx " - Fixing:" (:ttl r) "->" fixed-ttl)
        (swap! idx inc)
        (far/update-item config/dynamodb-opts notification/table-name
         {:user_id (:user_id r)
          :notify_at (:notify_at r)}
         {:update-expr "SET #k = :v"
          :expr-attr-names {"#k" "ttl"}
          :expr-attr-vals  {":v" fixed-ttl}
          :return :all-new})))))