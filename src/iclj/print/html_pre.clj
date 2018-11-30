(ns iclj.print.html-pre
  "Preformatted HTML"
  (:require [net.cgrand.packed-printer.core :as core]
    [net.cgrand.packed-printer :as pp]
    [iclj.unrepl.elisions :as elisions]
    [clojure.string :as str]))

;; layout rules:
;; * if a collection needs multiple lines then
;;   there must be a line break (eventually preced by a comma)
;;   after its trail
;; * the trail is made of all closing delimiters and eventually a comma
;; * so basically trails need to be pushed

(defn markup [text markup-text]
  {:length (count text)
   :text markup-text
   :start-length (count text)
   :start-text markup-text
   :br-after? true})

(defn esc [s]
  (str/replace s #"[&<>]" {"&" "&amp;" "<" "&lt;" ">" "&gt;"}))

(defn plain [s]
  (let [e (esc s)]
    (if (= e s)
      s
      {:length (count s)
      :text e
      :start-length (count s)
      :start-text e
      :br-after? true})))

(defn nobr [text]
  {:length (count text)
   :text text
   :start-length (count text)
   :start-text text})

(defn opening [s i]
  (let [pre (str "<span class=coll><span class=punct>" (esc s) "</span><span class=items>")]
    {:start-length (count s)
     :start-text pre
     :length (count s)
     :text pre
     :indent i}))

(defn closing [s] 
  {:length (count s)
   :text (str "</span><span class=punct>" (esc s) "</span></span>")
   :br-after? true
   :indent -1})

(def delims
  (-> {}
    (into (map (fn [s] [s (opening s (count s))])) ["(" "[" "{" "#{"])
    (into (map (fn [s] [s (closing s)])) [")" "]" "}"])))

(def comma {:length 1
            :text "<span class=punct>,</span>"
            :br-after? true})

(def group-close
  {:length 0
   :text "</span>"
   :br-after? true
   :indent -1})

(def space {:length 1 :text " " :start-length 0 :start-text ""})

(defn spans
  "Turns x into a collection of spans for layout. Options supported are:
 * kv-indent the amount of spaces by which to indent a value when it appears
   at the start of a line (default 2),
 * coll-indents a map of collection start delimiters (as strings) to the amount
   by which to indent (default: length of the delimiter)."
  [x {:keys [kv-indent coll-indents] :or {kv-indent 2 coll-indents {}}}]
  (let [delims (into delims (map (fn [[s i]] [s (opening s i)])) coll-indents)
        group-open
        {:length 0
         :text "<span class=kv>"
         :start-length 0
         :start-text "<span class=kv>"
         :indent kv-indent}
        meta-open-pre "<span class=meta><span class=punct>^</span>"
        meta-open {:length 1
                   :text meta-open-pre
                   :start-length 1
                   :start-text meta-open-pre
                   :indent kv-indent}]
    (letfn [(coll-spans
              ([x] (coll-spans x [space] spans))
              ([x sp spans]
                (sequence (comp (map spans) (interpose sp) cat) x)))
            (kv-spans [[k v]]
              (if (elisions/elision? k)
                (spans v)
                (-> [group-open] (into (spans k)) (conj space) (into (spans v)) (conj group-close))))
            (spans [x]
              (cond
                (keyword? x) (let [s (str x)] [(markup s (str "<span class=keyword>" (esc s) "</span>"))]) ; cyan
                (tagged-literal? x)
                (case (:tag x)
                  unrepl/... [(let [s (if-some [id (elisions/intern x)] (str "/" id) "/\u29B0")]
                                ; TODO
                                (markup "…" "<a href=#>…</a>"))]
                  clojure/var [(plain (str "#'" (:form x)))]
                  unrepl/meta (let [[m v] (:form x)]
                                (concat (cons meta-open (spans m)) (cons space (spans v)) [group-close]))
                  unrepl.java/class [(markup (str (:form x)) (str "<span class=class>" (esc (:form x)) "</span>"))] ; to distinguish from symbols
                  unrepl/string (let [[s e] (:form x)
                                      s (plain (pr-str s))] (cons (nobr s) (spans e))) ; TOFIX nobr
                  unrepl/ratio (let [[n d] (:form x)]
                                 [(plain (str n "/" d))])

                  unrepl/pattern (let [[n d] (:form x)]
                                   [(plain (pr-str (re-pattern (:form x))))])
                  unrepl/lazy-error ; TODO
                  (concat [group-open (markup (str "/lazy-error")
                                     (str "\33[31m/lazy-error\33[m"))
                           space]
                    (spans (-> x :form :form :cause))
                    [space
                     (let [cmd (str "/" (elisions/intern (:form x)))]
                       (markup cmd (str "\33[31m\33[4m" cmd "\33[m")))
                     group-close])
                  error (concat
                           [group-open (markup "#error" "<span class=error>#error</span>")
                            space]
                           (spans (:form x)) [group-close])
                  (concat [group-open (str "#" (pr-str (:tag x))) space] (spans (:form x)) [group-close]))
                (vector? x) (concat [(delims "[")] (coll-spans x) [(delims "]")])
                (set? x) (concat [(delims "#{")]
                           (coll-spans (if-some [e (some #(when (elisions/elision? %) %) x)]
                                         (concat (disj x e) [e])
                                         x))
                           [(delims "}")])
                (seq? x) (concat [(delims "(")] (coll-spans x) [(delims ")")])
                (map? x) (if-some [kv (find x elisions/unreachable)]
                           (concat [(delims "{")] (coll-spans (concat (dissoc x elisions/unreachable) [kv]) [comma space] kv-spans) [(delims "}")])
                           (concat [(delims "{")] (coll-spans x [comma space] kv-spans) [(delims "}")]))
                :else [(pr-str x)]))]
      (spans x))))

(defmethod core/spans [:text :unrepl/edn-html-pre] [x to-as opts]
  (spans x opts))

(defn html [x]
  (with-out-str
    (print "<div style='white-space: pre; font-family: monospace;'><style>.punct {color: #aaa;}.class {color: orange;}.keyword {color: teal; font-weight: bold;}</style>")
    (pp/pprint x  :as :unrepl/edn-html-pre :strict 20 :width 72)
    (print "</div>")))
