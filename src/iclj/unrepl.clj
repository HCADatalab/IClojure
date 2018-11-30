(ns iclj.unrepl
  (:require [iclj.messages :refer :all]
            [clojure.core.async :as a]
            [clojure.walk :as w]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]
            [cheshire.core :as json]
            [iclj.print.text]
            [iclj.print.html-pre :as html]
            [net.cgrand.packed-printer :as pp]
            [iclj.unrepl.elisions :as elisions]
            [clojure.tools.deps.alpha :as deps]))

(defmacro ^:private while-some [binding & body]
  `(loop []
     (when-some ~binding
       ~@body
       (recur))))

(defn pipe []
  (let [pipe (java.nio.channels.Pipe/open)]
    {:reader (-> pipe .source java.nio.channels.Channels/newInputStream (java.io.InputStreamReader. "UTF-8"))
     :writer (-> pipe .sink java.nio.channels.Channels/newOutputStream (java.io.OutputStreamWriter. "UTF-8"))}))

(defn- ^java.io.PushbackReader hello-sync
  "Takes the output of a freshly started unrepl and waits for the unrepl welcome message.
   Returns a reader suitable for edn/read."
  [out]
  (let [^java.io.BufferedReader out (cond-> out (not (instance? java.io.BufferedReader out)) java.io.BufferedReader.)]
    (loop [] ; sync on hello
      (when-some [line (.readLine out)]
        (if-some [[_ hello] (re-matches #".*?(\[:unrepl/hello.*)" line)]
          (doto (java.io.PushbackReader. out (inc (count hello)))
            (.unread (int \newline))
            (.unread (.toCharArray hello)))
          (recur))))))

(defn edn-reader-ch
  "Takes a reader suitable for edn/read (as returned by hello-sync) and returns a channel to which read
   edn messages are written.
   Closing the channel closes the reader."
  [out]
  (let [out (hello-sync out)
        ch (a/chan)
        eof (Object.)]
    (a/thread
      (try
        (loop []
          (let [x (edn/read {:eof eof :default tagged-literal} out)]
            (cond
              (identical? eof x) (a/close! ch)
              (a/>!! ch x) (recur)
              :else (a/close! ch))))
        (finally
          (.close out))))
    ch))

(defn sideloader-loop [^java.io.Writer in out ^ClassLoader cl]
  (let [out (java.io.PushbackReader. out)]
    (a/thread ; edn tuples reader
      (while (not= (edn/read out) [:unrepl.jvm.side-loader/hello]))
      (loop []
        (let [[type s] (edn/read out)
              path (case type
                     :resource s
                     :class (str (str/replace s "." "/") ".class")
                     nil)
              resp (when-some [url (when path
                                     (or (io/resource (str "blob-libs/" path))
                                       (.getResource cl path)))]
                     (let [bout (java.io.ByteArrayOutputStream.)]
                       (with-open [cin (.openStream url)
                                   b64out (.wrap (java.util.Base64/getEncoder) bout)]
                         (io/copy cin b64out))
                       (String. (.toByteArray bout) "ASCII")))]
          (binding [*out* in] (prn resp)))
        (recur)))))

(defn emit-action [template args-map]
  (w/postwalk
    (fn [x]
      #(cond-> %
         (and (tagged-literal? %) (= (:tag %) :unrepl/param)) (-> :form args-map)))
    template))

(defn- client-loop [^java.io.Writer in out & {:keys [on-hello]}]
  (let [to-eval (a/chan)
        unrepl-input (a/chan)
        unrepl-output (a/chan)
        [eof-tag :as eof] [(Object.)]
        out (hello-sync out)]
    (a/thread ; edn tuples reader
      (loop []
        (let [[tag :as msg] (edn/read {:eof eof :default tagged-literal} out)]
          (prn 'GOT msg) 
          (when-not (= eof-tag tag)
            (a/>!! unrepl-output msg)
            (if (= :bye tag)
              (a/close! unrepl-output msg)
              (recur)))))
      (a/close! unrepl-output))
    (a/thread ; text writer
      (loop []
        (when-some [^String s (a/<!! unrepl-input)]
          ; framed input because notebook style
          (doto in (.write (prn-str `(eval (read-string ~s)))) .flush)
          (recur))))
    (a/go-loop [offset 0 all-caught-up true eval-id nil msgs-out nil]
           (some-> msgs-out (cond-> all-caught-up a/close!))
           (let [[val ch] (a/alts! (cond-> [unrepl-output] all-caught-up (conj to-eval)))]
             (condp = ch
               to-eval (let [[code msgs] val
                             code (str code \newline)
                             offset (+ offset (count code))]
                         (a/>! unrepl-input code)
                         (recur offset false eval-id msgs))
               unrepl-output (if-some [[tag payload id] val]
                               (case tag
                                 :unrepl/hello
                                 (do
                                   (when on-hello (on-hello payload))
                                   (recur offset all-caught-up id msgs-out))
                                 ; misaligned forms are not tracked because all input is framed
                                 #_#_:read
                                   (recur offset (transduce (take-while (fn [[end-offset]] (< end-offset (:offset payload)))) 
                                                   (completing (fn [evals _] (pop evals))) evals evals))
                                 :prompt
                                 (recur offset (<= offset (:offset payload)) id msgs-out)
                                 #_#_:started-eval nil
                                 
                                 (:eval :exception) (do (some-> msgs-out (doto (a/>! val) a/close!)) (recur offset all-caught-up nil nil))
                          
                                 ; else
                                 ; todo filter by id
                                 (do (some-> msgs-out (a/>! val)) (recur offset all-caught-up eval-id msgs-out)))
                               (do (some-> msgs-out (a/>! [:err "Connection to repl has been lost!" nil]) a/close!) (recur offset all-caught-up nil nil))))))
    to-eval))

(defn unrepl-connect
  [connector #_class-loader]
  (let [{:keys [^java.io.Writer in ^java.io.Reader out]} (connector)]
    (with-open [blob (io/reader (io/resource "unrepl-blob.clj") :encoding "UTF-8")]
      (io/copy blob in))
    (.flush in)
    {:in in
     :edn-out (edn-reader-ch out)}))

(defn aux-connect
  [connector form]
  (let [{:keys [^java.io.Writer in ^java.io.Reader out]} (connector)]
    (doto in
      (.write (prn-str form))
      .flush)
    {:in in
     :edn-out (edn-reader-ch out)}))

(defn unrepl-process
  "Manages one connection at a time"
  [{edn-out :edn-out ^java.io.Writer writer :in} input-ch output-ch]
  (let [vstate (volatile! {:offset 0
                           :pending-chs (sorted-map)
                           :ids-to-ch {}})
        get-ch (fn [[tag payload id]]
                 (let [{:keys [pending-chs ids-to-ch] :as state} @vstate]
                   (or (ids-to-ch id)
                     (when-let [ch (and id (= :read tag) (first (vals (subseq pending-chs >= (+ (:offset payload) (:len payload))))))]
                       (vswap! vstate update :ids-to-ch assoc id ch)
                       ch)
                     output-ch)))
        unmap-ch! (fn [[_ _ id]]
                    (vswap! vstate update :ids-to-ch dissoc id))]
    (a/thread
      (while-some [[msg ch] (a/alts!! [input-ch edn-out])]
        ; a big loop/recur was a bit unwieldly
        (condp = ch
          edn-out ; edn triple received from repl
          (let [ch (get-ch msg)]
            (when-not (a/>!! ch msg)
              (unmap-ch! msg)
              (a/>!! output-ch msg)))
          input-ch ; user input (as [string ch]) received
          (let [[^String code ch] msg
                {:keys [offset] :as state} @vstate
                offset (+ offset (count code))]
            (vreset! vstate (-> state (assoc :offset offset)
                              (update :pending-chs assoc offset ch)))
            (doto writer (.write code) .flush)))))))

(defn stacktrace-string
  "Return a nicely formatted string."
  [msg]
  (when-let [st (:stacktrace msg)]
    (let [clean (->> st
                     (filter (fn [f] (not-any? #(= "dup" %) (:flags f))))
                     (filter (fn [f] (not-any? #(= "tooling" %) (:flags f))))
                     (filter (fn [f] (not-any? #(= "repl" %) (:flags f))))
                     (filter :file))
          max-file (apply max (map count (map :file clean)))
          max-name (apply max (map count (map :name clean)))]
      (map #(format (str "%" max-file "s: %5d %-" max-name "s")
                    (:file %) (:line %) (:name %))
           clean))))
; IPython.notebook.kernel.execute("(+ 1 1)", {iopub: {output: function() { console.log("reply", arguments) } }})
