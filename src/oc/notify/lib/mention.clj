(ns oc.notify.lib.mention
  "Parse mentions out of body HTML."
  (:require
    [clojure.string :as s]
    [hickory.core :as html]
    [hickory.select :as sel]))

(defn- wrap-in-div [html] (str "<div>" (s/trim html) "</html>"))

(defn- extract-parents
  "Extract all parent div and p elements that contain a mention."
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

(defn extract-mentions
  "
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

)