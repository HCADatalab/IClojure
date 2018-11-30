(ns
 compliment.sources.resources$VQe0QBICU2GS9RUCMJrrTs5RY4A
 "Completion for bundled resource files."
 (:require
  [clojure.java.io :as io]
  [compliment.sources$YoJ2fCFJdd1s$v$nIr5hrOwpsGw :refer [defsource]]
  [compliment.utils$GBoOeZzQY6UxS5g46b3tb9$CLec :as utils])
 (:import java.io.File java.net.URLConnection))


(defn inside-resource-call?
  "If context is not nil, check if prefix inside the string in a
  clojure.java.io/resource call."
  [ctx]
  (when (and ctx)
    (let [[str call] ctx
          fn (first (:form call))]
      (and (string? (:form str))
           (list? (:form call))
           (symbol? fn)
           (= (name fn) "resource")))))

(defn candidates
  "Returns list of completions for project resources if within certain context."
  [prefix _ context]
  (when (inside-resource-call? context)
    (for [^String res (utils/project-resources)
          :when (.startsWith res prefix)]
      {:candidate res
       :type :resource})))

(defn doc
  "Documentation function for project resources."
  [resource-name _]
  (try (let [^String filename (.getFile (io/resource resource-name))]
         (format "File type: %s, size: %d bytes"
                 (or (URLConnection/guessContentTypeFromName filename)
                     "application/unknown")
                 (.length (io/file filename))))
       (catch Exception ex nil)))

(defsource ::resources
  :candidates #'candidates
  :doc #'doc)
