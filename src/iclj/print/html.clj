(ns iclj.print.html
  "Dynamic HTML :-)"
  (:require [iclj.unrepl.elisions :as elisions]
    [clojure.string :as str]))

;; layout rules:
;; * if a collection needs multiple lines then
;;   there must be a line break (eventually preced by a comma)
;;   after its trail
;; * the trail is made of all closing delimiters and eventually a comma
;; * so basically trails need to be pushed

(defn esc [s]
  (str/replace s #"[&<>]" {"&" "&amp;" "<" "&lt;" ">" "&gt;" "'" "&quot;"}))

(defn li
  "Turns x into a list item."
  [x]
  (letfn [(spli [x]
            (cons "<li class=space>" (li x)))
          (lis [x]
            (when-some [[x & xs] (seq x)]
              (concat (li x) (mapcat spli xs))))
          (kv-spli [[k v]]
            (concat ["<li class=space><li><ul>"] (li k) (spli v) ["</ul>"]))
          (kv-lis [kvs]
            (when-some [[[k v] & kvs] (seq kvs)]
              (concat ["<li><ul>"] (li k) (spli v) ["</ul>"] (mapcat kv-spli kvs))))]
    (cond
      (keyword? x) ["<li class=keyword>" (esc (str x))] ; cyan
      (tagged-literal? x)
      (case (:tag x)
        unrepl/... (if-some [form (:get (:form x))]
                     ["<li class=elision data-expr='" (esc (pr-str form)) "'>…"]
                     ["<li class=elision-deadend>⦰"])
        clojure/var ["<li class=var>#'" (esc (:form x))]
        unrepl/meta (let [[m v] (:form x)]
                      (concat ["<li class=meta>^<ul>"] (li m) (li v) ["</ul>"]))
        unrepl.java/class ["<li class=class>" (esc (:form x))] ; to distinguish from symbols
        #_#_unrepl/string (let [[s e] (:form x)
                                s (plain (pr-str s))] (cons (nobr s) (spans e))) ; TOFIX nobr
        unrepl/ratio (let [[n d] (:form x)]
                       [(str "<li>" n "/" d)])
        
        unrepl/pattern (let [[n d] (:form x)]
                         ["<li class=pattern>" (esc (pr-str (re-pattern (:form x))))])
        #_#_unrepl/lazy-error ; TODO
          (concat [group-open (markup (str "/lazy-error")
                                (str "\33[31m/lazy-error\33[m"))
                   space]
            (spans (-> x :form :form :cause))
            [space
             (let [cmd (str "/" (elisions/intern (:form x)))]
               (markup cmd (str "\33[31m\33[4m" cmd "\33[m")))
             group-close])
        #_#_error (concat
                    [group-open (markup "#error" "<span class=error>#error</span>")
                     space]
                    (spans (:form x)) [group-close])
        (concat ["<li class=taglit>#<ul>"] (li (:tag x)) (li (:form x)) ["</ul>"]))
      (vector? x) 
      (concat ["<li class=vector>[<ul>"] (lis x) ["<li class=trail>]</ul>"])
      (set? x)
      (concat ["<li class=set>#{<ul>"] (lis (if-some [e (some #(when (elisions/elision? %) %) x)]
                                              (concat (disj x e) [e])
                                              x)) ["<li class=trail>}</ul>"])
      (seq? x) (concat ["<li class=seq>(<ul>"] (lis x) ["<li class=trail>)</ul>"])
      (map? x) (concat ["<li class=map>{<ul>"]
                 (if-some [kv (find x elisions/unreachable)]
                   ; (dissoc x elisions/unreachable) [kv]
                   (concat (kv-lis (dissoc x elisions/unreachable))
                     ["<li>TODO"])
                   (kv-lis x))
                 ["<li class=trail>}</ul>"])
      :else ["<li>" (pr-str x)])))

(defn html [x]
  (apply str (concat ["<ul>"] (li x) ["</ul>"])))
