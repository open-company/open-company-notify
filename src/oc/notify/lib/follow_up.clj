(ns oc.notify.lib.follow-up
  (:require
    [clojure.set :as set]))

(defn assigned-follow-ups
  "Returns newly assigned follow-ups that are not follow-ups for the author."
  [author prior-follow-ups current-follow-ups]
  (let [prior (set (map (comp :user-id :assignee) (remove :completed? prior-follow-ups)))
        current (set (map (comp :user-id :assignee) (remove :completed? current-follow-ups)))
        diff-set (set/difference current prior)
        new (filter #(diff-set (-> % :assignee :user-id)) current-follow-ups)]
    (remove #(= (:user-id author) (-> % :assignee :user-id)) new)))