(ns oc.notify.db.migrations
  "Lein main to migrate Dynamo data."
  (:require [clojure.string :as s]
            [oc.notify.config :as c]
            [oc.lib.db.migrations :as m])
  (:gen-class))

(defn- run-migrations
  "Given a list of migrations, run them. Abort if any doesn't succeed."
  [conn migrations-dir migration-names]
  (doseq [migration-name (map #(second (re-matches #".*\/(.*).edn$" %)) (map str migration-names))]
    (assert (true? (m/run-migration conn migrations-dir migration-name))))
  :ok)

(defn- migrate 
  "
  Run all migrations on this DB.
  "
  [dynamodb-opts migrations-dir]
  {:pre [(map? dynamodb-opts)
         (string? migrations-dir)]}
  ;; Run the migrations
  (println "\nRunning migrations.")
  (run-migrations dynamodb-opts migrations-dir
    (filter #(s/ends-with? % ".edn") (file-seq (clojure.java.io/file migrations-dir))))
  (println "Migrations complete.")
  (System/exit 0)) ; gets hung when running Faraday commands in migrations, so force an exit

(defn -main
  "
  Run create or migrate from lein.

  Usage:

  lein create-migration <name>

  lein migrate-db
  "
  [which & args]
  (cond 
    (= which "migrate") (migrate c/dynamodb-opts c/migrations-dir)
    (= which "create") (apply m/create c/migrations-dir c/migration-template args)
    :else (println "Unknown action: " which)))