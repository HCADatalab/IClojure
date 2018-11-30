(ns
 unrepl.actions.complete$heCV5LnniC8hloyc_7Ia3OvIZpU
 (:require
  [compliment.core$0f1Ub2878gVAnHk7iRyxxTvfmHs :as c]
  [compliment.context$Fr$2t2uGMnnxNlMe5Ezkf1XJWWY :as ctx]
  [clojure.edn :as edn]))


(defn complete-path [^String path]
  (let [file (java.io.File. path)
        n (count path)
        candidates
        (map #(subs % n)
          (concat
           (when-not (.endsWith path "/")
             (concat
               (when (.isDirectory file) [(str path "/")])
               (some-> file .getParentFile
                 (.listFiles (let [prefix (.getName file)]
                               (reify java.io.FilenameFilter
                                 (accept [_ _ name]
                                   (.startsWith name prefix)))))
                 (map #(.getPath ^java.io.File %)))))
           (map #(.getPath ^java.io.File %) (.listFiles file))))]
    (for [candidate candidates]
      {:candidate candidate
       :left-del 0
       :right-del 0})))

(defn complete
  "Completion action, takes 3 arguments: some code (as string) to the left of the cursor,
   some code to the right of the cursor, the namespace name (as a symbol).
   Returns a list of candidates, each item of the list is a map containing at least:
   * :candidate (a string), the text to insert,
   * :left-del and :right-del (numbers of character to delete to the left and right of the
     cursor before inserting candidate text.
   Maps may contain additional namespaced fields."
  [left right ns-name]
  (if-some [[_ partial-string] (re-matches #"^(?:[^\"\\;]*|\\.|;[^\n]*?\n|\"(?:[^\"\\]|\\.)*\")*(\"[^\"\\]*)" left)]
    (complete-path (edn/read-string (str partial-string \")))
    (let [[_ left prefix] (re-matches #"(.*?)([^\s,\";@^`~()\[\]{}]*)" left)
          [_ suffix right] (re-matches #"([^\s,\";@^`~()\[\]{}]+)?(.*)" right)
          context (str left ctx/prefix-placeholder suffix right)
          len (count prefix)]
      (map (fn [{:keys [candidate type ns]}]
             ; should we filter or do something clever with the suffix?
             {:candidate (cond-> candidate suffix (str " ")) ; autospace
              :left-del len
              :right-del 0
              :compliment/type type
              :compliment/ns ns})
        (c/completions prefix {:ns ns-name :context context})))))



