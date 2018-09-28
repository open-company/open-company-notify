(ns dev
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :as ctnrepl]
            [oc.lib.db.pool :as pool]
            [oc.notify.config :as c]
            [oc.notify.app :as app]
            [oc.notify.components :as components]))

(def system nil)
(def conn nil)

(defn init
  ([] (init c/notify-server-port))
  ([port]
  (alter-var-root #'system (constantly (components/notify-system {:httpkit {:handler-fn app/app
                                                                            :port port}
                                                                  :sqs-consumer {
                                                                    :sqs-queue c/aws-sqs-notify-queue
                                                                    :message-handler app/sqs-handler
                                                                    :sqs-creds {
                                                                      :access-key c/aws-access-key-id
                                                                      :secret-key c/aws-secret-access-key}}})))))

(defn bind-conn! []
  (alter-var-root #'conn (constantly (pool/claim (get-in system [:db-pool :pool])))))

(defn- start⬆ []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system (fn [s] (when s (component/stop s))))
  (println (str "When you're ready to start the system again, just type: (go)\n")))

(defn go

  ([] (go c/notify-server-port))

  ([port]
  (init port)
  (start⬆)
  (bind-conn!)
  (app/echo-config port)
  (println (str "Now serving notifications from the REPL.\n"
                "A DB connection is available with: conn\n"
                "When you're ready to stop the system, just type: (stop)\n"))
  port))

(defn reset []
  (stop)
  (ctnrepl/refresh :after 'user/go))