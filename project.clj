(defproject open-company-notify "0.1.0-SNAPSHOT"
  :description "OpenCompany Notify Service"
  :url "https://github.com/open-company/open-company-notify"
  :license {
    :name "Mozilla Public License v2.0"
    :url "http://www.mozilla.org/MPL/2.0/"
  }

  :min-lein-version "2.7.1"

  ;; JVM memory
  :jvm-opts ^:replace ["-Xms512m" "-Xmx3072m" "-server"]

  ;; All profile dependencies
  :dependencies [
    ;; Lisp on the JVM http://clojure.org/documentation
    [org.clojure/clojure "1.10.0-alpha6"]
    ;; Command-line parsing https://github.com/clojure/tools.cli
    [org.clojure/tools.cli "0.3.7"] 
    ;; Web application library https://github.com/ring-clojure/ring
    [ring/ring-devel "1.7.0-RC1"]
    ;; Web application library https://github.com/ring-clojure/ring
    ;; NB: clj-time pulled in by oc.lib
    ;; NB: joda-time pulled in by oc.lib via clj-time
    ;; NB: commons-codec pulled in by oc.lib
    [ring/ring-core "1.7.0-RC1" :exclusions [clj-time joda-time commons-codec]]
    ;; CORS library https://github.com/jumblerg/ring.middleware.cors
    [jumblerg/ring.middleware.cors "1.0.1"]
    ;; Ring logging https://github.com/nberger/ring-logger-timbre
    ;; NB: com.taoensso/encore pulled in by oc.lib
    ;; NB: com.taoensso/timbre pulled in by oc.lib
    [ring-logger-timbre "0.7.6" :exclusions [com.taoensso/encore com.taoensso/timbre]] 
    ;; Web routing https://github.com/weavejester/compojure
    [compojure "1.6.1"]
    ;; DynamoDB client https://github.com/ptaoussanis/faraday
    ;; NB: com.amazonaws/aws-java-sdk-dynamodb is pulled in by amazonica
    ;; NB: joda-time is pulled in by clj-time
    ;; NB: encore pulled in from oc.lib
    [com.taoensso/faraday "1.10.0-alpha1" :exclusions [com.amazonaws/aws-java-sdk-dynamodb joda-time com.taoensso/encore]]
    ;; Faraday dependency, not pulled in? https://hc.apache.org/
    [org.apache.httpcomponents/httpclient "4.5.6"]

    ;; Library for OC projects https://github.com/open-company/open-company-lib
    [open-company/lib "0.16.10"]
    ;; In addition to common functions, brings in the following common dependencies used by this project:
    ;; httpkit - Web server http://http-kit.org/
    ;; core.async - Async programming and communication https://github.com/clojure/core.async
    ;; defun - Erlang-esque pattern matching for Clojure functions https://github.com/killme2008/defun
    ;; if-let - More than one binding for if/when macros https://github.com/LockedOn/if-let
    ;; Component - Component Lifecycle https://github.com/stuartsierra/component
    ;; Schema - Data validation https://github.com/Prismatic/schema
    ;; Timbre - Pure Clojure/Script logging library https://github.com/ptaoussanis/timbre
    ;; Amazonica - A comprehensive Clojure client for the AWS API https://github.com/mcohen01/amazonica
    ;; Faraday DynamoDB client https://github.com/ptaoussanis/faraday
    ;; Raven - Interface to Sentry error reporting https://github.com/sethtrain/raven-clj
    ;; Cheshire - JSON encoding / decoding https://github.com/dakrone/cheshire
    ;; clj-time - Date and time lib https://github.com/clj-time/clj-time
    ;; Environ - Get environment settings from different sources https://github.com/weavejester/environ
    ;; Sente - WebSocket server https://github.com/ptaoussanis/sente
  ]

  ;; All profile plugins
  :plugins [
    ;; Common ring tasks https://github.com/weavejester/lein-ring
    [lein-ring "0.12.4"]
    ;; Get environment settings from different sources https://github.com/weavejester/environ
    [lein-environ "1.1.0"]
  ]

  :profiles {

    ;; QA environment and dependencies
    :qa {
      :env {
        :hot-reload "false"
      }
      :dependencies [
        ;; Example-based testing https://github.com/marick/Midje
        ;; NB: org.clojure/tools.macro is pulled in manually
        ;; NB: clj-time is pulled in by oc.lib
        ;; NB: joda-time is pulled in by oc.lib via clj-time
        ;; NB: commons-codec pulled in by oc.lib
        [midje "1.9.2" :exclusions [joda-time org.clojure/tools.macro clj-time commons-codec]] 
        ;; Clojure WebSocket client https://github.com/cch1/http.async.client
        [http.async.client "1.3.0"]
        ;; Test Ring requests https://github.com/weavejester/ring-mock
        [ring-mock "0.1.5"]
      ]
      :plugins [
        ;; Example-based testing https://github.com/marick/lein-midje
        [lein-midje "3.2.1"]
        ;; Linter https://github.com/jonase/eastwood
        [jonase/eastwood "0.2.9"]
        ;; Static code search for non-idiomatic code https://github.com/jonase/kibit        
        [lein-kibit "0.1.6" :exclusions [org.clojure/clojure]]
      ]
    }

    ;; Dev environment and dependencies
    :dev [:qa {
      :env ^:replace {
        :log-level "debug"
        :aws-access-key-id "CHANGE-ME"
        :aws-secret-access-key "CHANGE-ME"
        :aws-sqs-email-queue "CHANGE-ME"
        :aws-sqs-bot-queue "CHANGE-ME"
        :aws-sqs-notify-queue "CHANGE-ME"
      }
      :plugins [
        ;; Check for code smells https://github.com/dakrone/lein-bikeshed
        ;; NB: org.clojure/tools.cli is pulled in by lein-kibit
        [lein-bikeshed "0.5.1" :exclusions [org.clojure/tools.cli]] 
        ;; Plugin for finding dead code (be thoughtful, lots of false positives) https://github.com/venantius/yagni
        [venantius/yagni "0.1.4"]
        ;; Runs bikeshed, kibit and eastwood https://github.com/itang/lein-checkall
        [lein-checkall "0.1.1"]
        ;; pretty-print the lein project map https://github.com/technomancy/leiningen/tree/master/lein-pprint
        [lein-pprint "1.2.0"]
        ;; Check for outdated dependencies https://github.com/xsc/lein-ancient
        [lein-ancient "0.6.15"]
        ;; Catch spelling mistakes in docs and docstrings https://github.com/cldwalker/lein-spell
        [lein-spell "0.1.0"]
        ;; Dead code finder https://github.com/venantius/yagni
        [venantius/yagni "0.1.4" :exclusions [org.clojure/clojure]]
      ]  
    }]
    :repl-config [:dev {
      :dependencies [
        ;; Network REPL https://github.com/clojure/tools.nrepl
        [org.clojure/tools.nrepl "0.2.13"]
        ;; Pretty printing in the REPL (aprint ...) https://github.com/razum2um/aprint
        [aprint "0.1.3"]
      ]
      ;; REPL injections
      :injections [
        (require '[aprint.core :refer (aprint ap)]
                 '[clojure.stacktrace :refer (print-stack-trace)]
                 '[clj-time.core :as t]
                 '[clj-time.format :as f]
                 '[clojure.string :as s]
                 '[cheshire.core :as json]
                 '[taoensso.faraday :as far]
                 '[oc.lib.jwt :as jwt]
                 '[oc.lib.db.common :as db-common]
                 '[oc.notify.app :refer (app)]
                 '[oc.notify.config :as config]
                 '[oc.notify.resources.notification :as notification]
                 )
      ]
    }]

    ;; Production environment
    :prod {
      :env {
        :env "production"
      }
    }
  }

  :repl-options {
    :welcome (println (str "\n" (slurp (clojure.java.io/resource "ascii_art.txt")) "\n"
                      "OpenCompany Notify REPL\n"
                      "\nReady to do your bidding... I suggest (go) or (go <port>) as your first command.\n"))
    :init-ns dev
  }

  :aliases {
    "build" ["do" "clean," "deps," "compile"] ; clean and build code
    "create-migration" ["run" "-m" "oc.notify.db.migrations" "create"] ; create a data migration
    "migrate-db" ["run" "-m" "oc.notify.db.migrations" "migrate"] ; run pending data migrations
    "start" ["do" "run"] ; start a development server
    "start!" ["with-profile" "prod" "do" "start"] ; start a server in production
    "autotest" ["with-profile" "qa" "do" "midje" ":autotest"] ; watch for code changes and run affected tests
    "test!" ["with-profile" "qa" "do" "clean," "build," "midje"] ; run all tests
    "repl" ["with-profile" "+repl-config" "repl"]
    "spell!" ["spell" "-n"] ; check spelling in docs and docstrings
    "bikeshed!" ["bikeshed" "-v" "-m" "120"] ; code check with max line length warning of 120 characters
    "ancient" ["ancient" ":all" ":allow-qualified"] ; check for out of date dependencies
  }

  ;; ----- Code check configuration -----

  :eastwood {
    ;; Disable some linters that are enabled by default:
    ;; contant-test - just seems mostly ill-advised, logical constants are useful in something like a `->cond` 
    ;; suspcious-experession - unfortunate, but it's failing on defrecord of a com.stuartsierra.component component
    :exclude-linters [:constant-test :suspicious-expression]

    ;; Enable some linters that are disabled by default
    :add-linters [:unused-namespaces :unused-private-vars] ; :unused-locals

    ;; Exclude testing namespaces
    :tests-paths ["test"]
    :exclude-namespaces [:test-paths]
  }

  :zprint {:old? false}
  
  ;; ----- API -----

  :ring {
    :handler oc.notify.app/app
    :reload-paths ["src"] ; work around issue https://github.com/weavejester/lein-ring/issues/68
  }

  :main oc.notify.app
)