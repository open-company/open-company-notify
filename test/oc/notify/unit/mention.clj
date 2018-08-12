(ns oc.notify.unit.mention
  (:require [midje.sweet :refer :all]
            [oc.notify.lib.mention :refer (extract-mentions)]))

(def body-mention "
  <span class='medium-editor-mention-at oc-mention'
      data-first-name='Albert'
      data-last-name='Camus'
      data-user-id='b7f2-41f2-be42'
      data-email='camus@combat.org'
      data-avatar-url='...'
      data-found='true'>
    @Albert Camus
  </span>
  ")

(def body-mention2
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

(def mention {})

(facts "about extracting mentions"

  (tabular (fact "nothing is extracting when there are no mentions"
    (extract-mentions ?body) => [])
    ?body
    ""
    "foo"
    "<p>foo</p>"
    "<span>foo</span>"
    "<p><span>foo</span></p>"
    "<div><span>foo</span></div><div><span>foo</span></div>"
    "<span class='medium-editor-mention-at oc-mention'>@camus</span>"
    "<p><span class='medium-editor-mention-at oc-mention'>@camus</span></p>"
    "<div><span class='medium-editor-mention-at oc-mention' data-found='false'>@camus</span></div>
     <p><span class='medium-editor-mention-at oc-mention' data-found='false'>@camus</span></p>")

  (tabular (fact "a single mention is extracted from the body"
    (count (extract-mentions ?body)) => 1)
    ?body
    body-mention
    (str "foo" body-mention)
    (str body-mention "bar")
    (str "foo" body-mention "bar")
    (str "<p>foo" body-mention "bar</p>")
    (str "<div>foo " body-mention " bar</div>")
    (str "<div>" body-mention " bar</div><p>No</p>")
    (str "<p>No</p><p>foo " body-mention "</p>")
    (str "<div>No</div><div>" body-mention body-mention2 "</div><p>No</p>"))

  (tabular (fact "multiple mentions are extracted from the body"
    (count (extract-mentions ?body)) => 2)
    ?body
    (str "<p>" body-mention "</p><div>" body-mention2 "</div>")
    (str "<p>foo" body-mention "bar</p><div>foo" body-mention2 "bar</div>")
    (str "<div>No</div><p>foo" body-mention "bar</p><div>foo" body-mention2 "bar</div><p>No</p>")
    (str "<div>No</div><p>foo" body-mention "bar" body-mention2 "</p><div>foo" body-mention2 "bar</div><p>No</p>"))

)