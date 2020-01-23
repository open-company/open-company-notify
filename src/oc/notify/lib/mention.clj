(ns oc.notify.lib.mention
  "Parse mentions out of body HTML."
  (:require
    [clojure.string :as s]
    [hickory.core :as html]
    [hickory.select :as sel]
    [hickory.render :as render]
    [oc.lib.user :as user]))

(defn- wrap-in-div [html]
  (if html (str "<div>" (s/trim html) "</div>") "<div></div>"))

(defn- parts-for
  "
  Given a container that has one or more mention spans in it, return each mention as a map with:

  :user-id - user that was mentioned
  :mention - the mention span
  :parent - the container
  "
  [container]
  (let [mention (sel/select
                  (sel/child
                    (sel/and
                      (sel/tag :span)
                      (sel/class :oc-mention)
                      (sel/attr :data-found #(= % "true"))))
                  container)]
    (map #(hash-map :user-id (-> % :attrs :data-user-id)
                    :mention %
                    :parent (render/hickory-to-html container)) mention)))

(defn- extract-parents
  "Extract all parent div and p elements that contain a mention span."
  [tree]
  (sel/select (sel/and
              (sel/or
                (sel/tag :p)
                (sel/tag :div))
              (sel/has-child
                (sel/and
                  (sel/tag :span)
                  (sel/class :oc-mention)
                  (sel/attr :data-found #(= % "true")))))
    tree))

(defn users-from-mentions [mentions]
  (map (fn [u]
         (let [user-name (user/name-for {:first-name (-> u :mention :attrs :data-first-name)
                                         :last-name (-> u :mention :attrs :data-last-name)})]
           (hash-map :user-id (:user-id u)
                     :name user-name
                     :avatar-url (-> u :mention :attrs :data-avatar-url))))
    (mapcat parts-for mentions)))

(defn mention-parents
  "
  Given a body, return all the P and DIV containers in the body that contain one or more mentions.

  Mentions are in the body of the post as a span inside a DIV or P tag:

  <div>...<span class='... oc-mention' >@...</span>...</div>
  <p>...<span class='... oc-mention' >@...</span>...</p>
  
  The mention span is:

  <span class='medium-editor-mention-at oc-mention'
        data-first-name='Albert'
        data-last-name='Camus'
        data-user-id='b7f2-41f2-be42'
        data-email='camus@combat.org'
        data-avatar-url='...'
        data-found='true'>
    @Albert Camus
  </span>

  The `data-found=true` is a crucial element as the span may be repeated in the body but w/o the data.
  For our purposes a mention is just every span element with `data-found=true`.
  "
  [body]
  (let [wrapped-body (wrap-in-div body) ; wrap the body in an element so it has a single root
        tree (first (map html/as-hickory (html/parse-fragment wrapped-body)))]
    (extract-parents tree)))

(defn new-mentions
  "
  Given a sequence of prior mentions (might be empty) and a sequence of new mentions (might be empty)
  return just the sequence of mentions that are in the new set but were not in the old set.

  The returned sequence will also be returned as a sequence of maps with the keys:

  :user-id - user that was mentioned
  :mention - the mention span
  :parent - the container
  "
  [prior-containers containers]
  (let [prior-mentions (flatten (map parts-for prior-containers))
        mentions (flatten (map parts-for containers))
        ;; multiple mentions of the same user aren't interesting
        deduped-prior-mentions (zipmap (map :user-id prior-mentions) prior-mentions) ; de-dupe by user-id
        deduped-mentions (zipmap (map :user-id mentions) mentions) ; de-dupe by user-id
        ;; only mentions for users that are in the mentions but not in the prior mentions
        new-mentions (filter #(not (get deduped-prior-mentions (:user-id %))) (vals deduped-mentions))]
    (vec new-mentions)))

(comment

  ;; REPL notes

  (in-ns 'oc.notify.lib.mention)
  (require '[aprint.core :refer (aprint ap)])
  
  (require '[oc.notify.lib.mention] :reload)

  (def mention1
    "<span
      class='medium-editor-mention-at oc-mention'
      data-first-name='Albert'
      data-last-name='Camus'
      data-user-id='b7f2-41f2-be42'
      data-email='camus@combat.org'
      data-avatar-url='...'
      data-found='true'>
      @Albert Camus
    </span>")

  (def mention2
    "<span
      class='medium-editor-mention-at oc-mention'
      data-first-name='Jean-Paul'
      data-last-name='Sartre'
      data-user-id='b7f2-41f2-4242'
      data-email='sartre@combat.org'
      data-avatar-url='...'
      data-found='true'>
      @sartre
    </span>")

  (def body1 (str "<p>I'm not sure " mention1 ", what do you think about this?</p>"))
  (def body2 (str "<p>Existence precedes essence...</p>
                   <div>I'm not sure " mention1 ", what do you think about this?</div>
                   <p>All I know is " mention2 " says it's true.</p>"))
  (def body3 (str "<p>No.</p><p>I'm not sure " mention2 ", what do you think about this " mention1 "?</p><div>No</div>"))

  (def tree1 (first (map html/as-hickory (html/parse-fragment (wrap-in-div body1)))))
  (def tree2 (first (map html/as-hickory (html/parse-fragment (wrap-in-div body2)))))
  (def tree3 (first (map html/as-hickory (html/parse-fragment (wrap-in-div body3)))))

  (sel/select sel/any tree1)

  ;; These give us each p or div that contains 1 or more mentions
  (aprint (extract-parents tree1))
  (aprint (extract-parents tree2))
  (aprint (extract-parents tree3))

  (new-mentions [] (extract-parents tree1))
  (new-mentions [] (extract-parents tree2))
  (new-mentions [] (extract-parents tree3))
  (new-mentions (extract-parents tree1) (extract-parents tree1))
  (new-mentions (extract-parents tree3) (extract-parents tree3))
  (new-mentions (extract-parents tree1) (extract-parents tree3))

)