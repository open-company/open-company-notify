(ns oc.notify.lib.follow-up
  (:require
    [clojure.set :as set]))

(defn assigned-follow-ups [author prior-follow-ups current-follow-ups]
  "Returns newly assigned follow-ups that are not follow-ups for the author."
  (let [prior (set (remove :completed prior-follow-ups))
        current (set (remove :completed current-follow-ups))
        new (set/difference current prior)]
    (remove #(= (:user-id author) (-> % :assignee :user-id)) new)))