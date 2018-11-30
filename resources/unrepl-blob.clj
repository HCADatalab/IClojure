(clojure.core/let [nop (clojure.core/constantly nil)
done (promise)
e (clojure.core/atom eval)]
(-> (create-ns 'unrepl.repl$gOQFDUuzHjLIF1eKBU9gtZ8I4Xk)
(intern '-init-done)
(alter-var-root
(fn [v]
(if (instance? clojure.lang.IDeref v)
(do
(reset! e (if-some [ex @v]
(fn [_] (throw ex))
nop))
v)
done))))
(clojure.main/repl
:read #(let [x (clojure.core/read)] (clojure.core/case x <<<FIN (do (deliver done nil) %2) x))
:prompt nop
:eval #(@e %)
:print nop
:caught #(do (set! *e %) (reset! e nop) (prn [:unrepl.upgrade/failed %]))))
(ns unrepl.core (:refer-clojure :exclude [read eval print]))
(def ^:once ^:private loaded-by "unrepl.repl")
(def ^:once ^:dynamic *string-length* 80)
(def ^:once ^:dynamic ^{:arglists '([x]) :doc "Atomically machine-prints its input (a triple) to the output stream."} write)
(defn ^:once non-eliding-write "use with care" [x]
(binding [*print-length* Long/MAX_VALUE
*print-level* Long/MAX_VALUE
*string-length* Long/MAX_VALUE]
(write x)))
(declare ^:once ^:dynamic read ^:once ^:dynamic print ^:once ^:dynamic eval)(ns
unrepl.printer$Z4aBFi34iyy94VC1WUZQs1VjhAE
(:require
[clojure.string :as str]
[clojure.edn :as edn]
[clojure.main :as main]
[unrepl.core :as unrepl]))
(def ^:dynamic *print-budget*)
(def defaults {#'*print-length* 10
#'*print-level* 8
#'unrepl/*string-length* 72})
(defn ensure-defaults [bindings]
(let [bindings (merge-with #(or %1 %2) bindings defaults)]
(assoc bindings #'*print-budget*
(long (min (* 1N (bindings #'*print-level*) (bindings #'*print-length*)) Long/MAX_VALUE)))))
(defprotocol MachinePrintable
(-print-on [x write rem-depth]))
(defn print-on [write x rem-depth]
(let [rem-depth (dec rem-depth)
budget (set! *print-budget* (dec *print-budget*))]
(if (and (or (neg? rem-depth) (neg? budget)) (pos? (or *print-length* 1)))
(binding [*print-length* 0]
(print-on write x 0))
(do
(when (and *print-meta* (meta x))
(write "#unrepl/meta [")
(-print-on (meta x) write rem-depth)
(write " "))
(-print-on x write rem-depth)
(when (and *print-meta* (meta x))
(write "]"))))))
(defn base64-encode [^java.io.InputStream in]
(let [table "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
sb (StringBuilder.)]
(loop [shift 4 buf 0]
(let [got (.read in)]
(if (neg? got)
(do
(when-not (= shift 4)
(let [n (bit-and (bit-shift-right buf 6) 63)]
(.append sb (.charAt table n))))
(cond
(= shift 2) (.append sb "==")
(= shift 0) (.append sb \=))
(str sb))
(let [buf (bit-or buf (bit-shift-left got shift))
n (bit-and (bit-shift-right buf 6) 63)]
(.append sb (.charAt table n))
(let [shift (- shift 2)]
(if (neg? shift)
(do
(.append sb (.charAt table (bit-and buf 63)))
(recur 4 0))
(recur shift (bit-shift-left buf 6))))))))))
(defn base64-decode [^String s]
(let [table "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
in (java.io.StringReader. s)
bos (java.io.ByteArrayOutputStream.)]
(loop [bits 0 buf 0]
(let [got (.read in)]
(when-not (or (neg? got) (= 61 #_\= got))
(let [buf (bit-or (.indexOf table got) (bit-shift-left buf 6))
bits (+ bits 6)]
(if (<= 8 bits)
(let [bits (- bits 8)]
(.write bos (bit-shift-right buf bits))
(recur bits (bit-and 63 buf)))
(recur bits buf))))))
(.toByteArray bos)))
(def ^:dynamic *elide*
"Function of 1 argument which returns the elision."
(constantly nil))
(def ^:dynamic *max-colls* 100)
(def ^:dynamic *realize-on-print*
"Set to false to avoid realizing lazy sequences."
true)
(defmacro ^:private blame-seq [& body]
`(try (seq ~@body)
(catch Throwable t#
 (list (tagged-literal 'unrepl/lazy-error t#)))))
(defn- may-print? [s]
(or *realize-on-print* (not (instance? clojure.lang.IPending s)) (realized? s)))
(declare ->ElidedKVs)
(defn- print-kvs
[write kvs rem-depth]
(let [print-length *print-length*]
(loop [kvs kvs i 0]
(if (and (< i print-length) (pos? *print-budget*))
(when-some [[[k v] & kvs] (seq kvs)]
(when (pos? i) (write ", "))
(print-on write k rem-depth)
(write " ")
(print-on write v rem-depth)
(recur kvs (inc i)))
(when (seq kvs)
(when (pos? i) (write ", "))
(write "#unrepl/... nil ")
(print-on write (tagged-literal 'unrepl/... (*elide* (->ElidedKVs kvs))) rem-depth))))))
(defn- print-vs
[write vs rem-depth]
(let [print-length *print-length*]
(loop [vs vs i 0]
(when-some [[v :as vs] (blame-seq vs)]
(when (pos? i) (write " "))
(if (and (< i print-length) (pos? *print-budget*) (may-print? vs))
(if (and (tagged-literal? v) (= (:tag v) 'unrepl/lazy-error))
(print-on write v rem-depth)
(do
(print-on write v rem-depth)
(recur (rest vs) (inc i))))
(print-on write (tagged-literal 'unrepl/... (*elide* vs)) rem-depth))))))
(defrecord WithBindings [bindings x]
MachinePrintable
(-print-on [_ write rem-depth]
(with-bindings (ensure-defaults bindings)
(-print-on x write *print-level*))))
(defrecord ElidedKVs [s]
MachinePrintable
(-print-on [_ write rem-depth]
(write "{")
(print-kvs write s rem-depth)
(write "}")))
(def atomic? (some-fn nil? true? false? char? string? symbol? keyword? #(and (number? %) (not (ratio? %)))))
(defn- as-str
"Like pr-str but escapes all ASCII control chars."
[x]
(cond
(string? x) (str/replace (pr-str x) #"\p{Cntrl}"
#(format "\\u%04x" (int (.charAt ^String % 0))))
(char? x) (str/replace (pr-str x) #"\p{Cntrl}"
#(format "u%04x" (int (.charAt ^String % 0))))
:else (pr-str x)))
(defmacro ^:private latent-fn [& fn-body]
`(let [d# (delay (binding [*ns* (find-ns '~(ns-name *ns*))] (eval '(fn ~@fn-body))))]
(fn
([] (@d#))
([x#] (@d# x#))
([x# & xs#] (apply @d# x# xs#)))))
(defrecord MimeContent [mk-in]
MachinePrintable
(-print-on [_ write rem-depth]
(with-open [in (mk-in)]
(write "#unrepl/base64 \"")
(write (base64-encode in))
(write "\""))))
(defn- mime-content [mk-in]
(when-some [e (*elide* (MimeContent. mk-in))]
{:content (tagged-literal 'unrepl/... e)}))
(def ^:dynamic *object-representations*
"map of classes to functions returning their representation component (3rd item in #unrepl/object [class id rep])"
{clojure.lang.IDeref
(fn [x]
(let [pending? (and (instance? clojure.lang.IPending x)
(not (.isRealized ^clojure.lang.IPending x)))
[ex val] (when-not pending?
(try [false @x]
(catch Throwable e
[true e])))
failed? (or ex (and (instance? clojure.lang.Agent x)
(agent-error x)))
status (cond
failed? :failed
pending? :pending
:else :ready)]
{:unrepl.ref/status status :unrepl.ref/val val}))
clojure.lang.AFn
(fn [x]
(-> x class .getName main/demunge))
java.io.File (fn [^java.io.File f]
(into {:path (.getPath f)}
(when (.isFile f)
{:attachment (tagged-literal 'unrepl/mime
(into {:content-type "application/octet-stream"
:content-length (.length f)}
(mime-content #(java.io.FileInputStream. f))))})))
java.awt.Image (latent-fn [^java.awt.Image img]
(let [w (.getWidth img nil)
h (.getHeight img nil)]
(into {:width w :height h}
{:attachment
(tagged-literal 'unrepl/mime
(into {:content-type "image/png"}
(mime-content #(let [bos (java.io.ByteArrayOutputStream.)]
(when (javax.imageio.ImageIO/write
(doto (java.awt.image.BufferedImage. w h java.awt.image.BufferedImage/TYPE_INT_ARGB)
(-> .getGraphics (.drawImage img 0 0 nil)))
"png" bos)
(java.io.ByteArrayInputStream. (.toByteArray bos)))))))})))
Object (fn [x]
(if (-> x class .isArray)
(seq x)
(str x)))})
(defn- object-representation [x]
(reduce-kv (fn [_ class f]
(when (instance? class x) (reduced (f x)))) nil *object-representations*))
(defn- class-form [^Class x]
(if (.isArray x) [(-> x .getComponentType class-form)] (symbol (.getName x))))
(def unreachable (tagged-literal 'unrepl/... nil))
(defn- print-tag-lit-on [write tag form rem-depth]
(write (str "#" tag " "))
(print-on write form rem-depth))
(defn- sat-inc [n]
(if (= Long/MAX_VALUE n)
n
(unchecked-inc n)))
(defn- print-trusted-tag-lit-on [write tag form rem-depth]
(print-tag-lit-on write tag form (sat-inc rem-depth)))
(defn StackTraceElement->vec'
"Constructs a data representation for a StackTraceElement"
{:added "1.9"}
[^StackTraceElement o]
[(symbol (.getClassName o)) (symbol (.getMethodName o)) (.getFileName o) (.getLineNumber o)])
(defn Throwable->map'
"Constructs a data representation for a Throwable."
{:added "1.7"}
[^Throwable o]
(let [base (fn [^Throwable t]
(merge {:type (symbol (.getName (class t)))
:message (.getLocalizedMessage t)}
(when-let [ed (ex-data t)]
{:data ed})
(let [st (.getStackTrace t)]
(when (pos? (alength st))
{:at (StackTraceElement->vec' (aget st 0))}))))
via (loop [via [] ^Throwable t o]
(if t
(recur (conj via t) (.getCause t))
via))
^Throwable root (peek via)
m {:cause (.getLocalizedMessage root)
:via (vec (map base via))
:trace (vec (map StackTraceElement->vec'
(.getStackTrace ^Throwable (or root o))))}
data (ex-data root)]
(if data
(assoc m :data data)
m)))
(def Throwable->map''
(if (neg? (compare (mapv *clojure-version* [:major :minor]) [1 9]))
Throwable->map'
Throwable->map))
(extend-protocol MachinePrintable
clojure.lang.TaggedLiteral
(-print-on [x write rem-depth]
(case (:tag x)
unrepl/... (binding
[*print-length* Long/MAX_VALUE
*print-level* Long/MAX_VALUE
*print-budget* Long/MAX_VALUE
unrepl/*string-length* Long/MAX_VALUE]
(write (str "#" (:tag x) " "))
(print-on write (:form x) Long/MAX_VALUE))
(print-tag-lit-on write (:tag x) (:form x) rem-depth)))
clojure.lang.Ratio
(-print-on [x write rem-depth]
(print-trusted-tag-lit-on write "unrepl/ratio"
[(.numerator x) (.denominator x)] rem-depth))
clojure.lang.Var
(-print-on [x write rem-depth]
(print-tag-lit-on write "clojure/var"
(when-some [ns (:ns (meta x))]
(symbol (name (ns-name ns)) (name (:name (meta x)))))
rem-depth))
Throwable
(-print-on [t write rem-depth]
(print-tag-lit-on write "error" (Throwable->map'' t) rem-depth))
Class
(-print-on [x write rem-depth]
(print-tag-lit-on write "unrepl.java/class" (class-form x) rem-depth))
java.util.Date (-print-on [x write rem-depth] (write (pr-str x)))
java.util.Calendar (-print-on [x write rem-depth] (write (pr-str x)))
java.sql.Timestamp (-print-on [x write rem-depth] (write (pr-str x)))
clojure.lang.Namespace
(-print-on [x write rem-depth]
(print-tag-lit-on write "unrepl/ns" (ns-name x) rem-depth))
java.util.regex.Pattern
(-print-on [x write rem-depth]
(print-tag-lit-on write "unrepl/pattern" (str x) rem-depth))
String
(-print-on [x write rem-depth]
(if (<= (count x) unrepl/*string-length*)
(write (as-str x))
(let [i (if (and (Character/isHighSurrogate (.charAt ^String x (dec unrepl/*string-length*)))
(Character/isLowSurrogate (.charAt ^String x unrepl/*string-length*)))
(inc unrepl/*string-length*) unrepl/*string-length*)
prefix (subs x 0 i)
rest (subs x i)]
(if (= rest "")
(write (as-str x))
(do
(write "#unrepl/string [")
(write (as-str prefix))
(write " ")
(print-on write (tagged-literal 'unrepl/... (*elide* rest)) rem-depth)
(write "]")))))))
(defn- print-coll [open close write x rem-depth]
(write open)
(print-vs write x rem-depth)
(write close))
(extend-protocol MachinePrintable
nil
(-print-on [_ write _] (write "nil"))
Object
(-print-on [x write rem-depth]
(cond
(atomic? x) (write (as-str x))
(map? x)
(do
(when (record? x)
(write "#") (write (.getName (class x))) (write " "))
(write "{")
(print-kvs write x rem-depth)
(write "}"))
(vector? x) (print-coll "[" "]" write x rem-depth)
(seq? x) (print-coll "(" ")" write x rem-depth)
(set? x) (print-coll "#{" "}" write x rem-depth)
:else
(print-trusted-tag-lit-on write "unrepl/object"
[(class x) (format "0x%x" (System/identityHashCode x)) (object-representation x)
{:bean {unreachable (tagged-literal 'unrepl/... (*elide* (ElidedKVs. (bean x))))}}]
(sat-inc rem-depth)))))
(defn edn-str [x]
(let [out (java.io.StringWriter.)
write (fn [^String s] (.write out s))
bindings (select-keys (get-thread-bindings) [#'*print-length* #'*print-level* #'unrepl/*string-length*])]
(with-bindings (into (ensure-defaults bindings) {#'*print-readably* true})
(print-on write x *print-level*))
(str out)))
(defn full-edn-str [x]
(binding [*print-length* Long/MAX_VALUE
*print-level* Long/MAX_VALUE
unrepl/*string-length* Integer/MAX_VALUE]
(edn-str x)))
(ns
unrepl.repl$gOQFDUuzHjLIF1eKBU9gtZ8I4Xk
(:require
[clojure.main :as m]
[unrepl.core :as unrepl]
[unrepl.printer$Z4aBFi34iyy94VC1WUZQs1VjhAE :as p]
[clojure.edn :as edn]
[clojure.java.io :as io]))
(defn classloader
"Creates a classloader that obey standard delegating policy.
   Takes two arguments: a parent classloader and a function which
   takes a keyword (:resource or :class) and a string (a resource or a class name) and returns an array of bytes
   or nil."
[parent f]
(proxy [clojure.lang.DynamicClassLoader] [parent]
(findResource [name]
(when-some [bytes (f :resource name)]
(let [file (doto (java.io.File/createTempFile "unrepl-sideload-" (str "-" (re-find #"[^/]*$" name)))
.deleteOnExit)]
(io/copy bytes file)
(-> file .toURI .toURL))))
(findClass [name]
(if-some [bytes (f :class name)]
(.defineClass ^clojure.lang.DynamicClassLoader this name bytes nil)
(throw (ClassNotFoundException. name))))))
(defn ^java.io.Writer tagging-writer
([write]
(proxy [java.io.Writer] []
(close [])
(flush [])
(write
([x]
(write (cond
(string? x) x
(integer? x) (str (char x))
:else (String. ^chars x))))
([string-or-chars off len]
(when (pos? len)
(write (subs (if (string? string-or-chars) string-or-chars (String. ^chars string-or-chars))
off (+ off len))))))))
([tag write]
(tagging-writer (fn [s] (write [tag s]))))
([tag group-id write]
(tagging-writer (fn [s] (write [tag s group-id])))))
(defn blame-ex [phase ex]
(if (::phase (ex-data ex))
ex
(ex-info (str "Exception during " (name phase) " phase.")
{::ex ex ::phase phase} ex)))
(defmacro blame [phase & body]
`(try ~@body
(catch Throwable t#
 (throw (blame-ex ~phase t#)))))
(defn atomic-write [^java.io.Writer w]
(fn [x]
(if (and (vector? x) (= (count x) 3))
(let [[tag payload id] x
s (blame :print (str "[" (p/edn-str tag)
" " (p/edn-str payload)
" " (p/edn-str id) "]"))]
(locking w
(.write w s)
(.write w "\n")
(.flush w)))
(let [s (blame :print (p/edn-str x))]
(locking w
(.write w s)
(.write w "\n")
(.flush w))))))
(definterface ILocatedReader
(setCoords [coords-map]))
(defn unrepl-reader [^java.io.Reader r]
(let [offset (atom 0)
last-reset (volatile! {:col-off 0 :line 0 :file (str (gensym "unrepl-reader-"))})
offset! #(swap! offset + %)]
(proxy [clojure.lang.LineNumberingPushbackReader clojure.lang.ILookup ILocatedReader] [r]
(getColumnNumber []
(let [{:keys [line col-off]} @last-reset
off (if (= (.getLineNumber this) line) col-off 0)]
(+ off (proxy-super getColumnNumber))))
(setCoords [{:keys [line col name]}]
(locking this
(when line (.setLineNumber this line))
(let [line (.getLineNumber this)
col-off (if col (- col (.getColumnNumber this)) 0)
name (or name (:file @last-reset))]
(vreset! last-reset {:line line :col-off col-off :file name})))
(:coords this))
(valAt
([k] (get this k nil))
([k not-found] (case k
:offset @offset
:coords {:offset @offset
:line (.getLineNumber this)
:col (.getColumnNumber this)
:file (:file @last-reset)}
not-found)))
(read
([]
(let [c (proxy-super read)]
(when-not (neg? c) (offset! 1))
c))
([cbuf]
(let [n (proxy-super read cbuf)]
(when (pos? n) (offset! n))
n))
([cbuf off len]
(let [n (proxy-super read cbuf off len)]
(when (pos? n) (offset! n))
n)))
(unread
([c-or-cbuf]
(if (integer? c-or-cbuf)
(when-not (neg? c-or-cbuf) (offset! -1))
(offset! (- (alength c-or-cbuf))))
(proxy-super unread c-or-cbuf))
([cbuf off len]
(offset! (- len))
(proxy-super unread cbuf off len)))
(skip [n]
(let [n (proxy-super skip n)]
(offset! n)
n))
(readLine []
(when-some [s (proxy-super readLine)]
(offset! (count s))
s)))))
(defn ensure-unrepl-reader
([rdr]
(if (instance? ILocatedReader rdr)
rdr
(unrepl-reader rdr)))
([rdr name]
(if (instance? ILocatedReader rdr)
rdr
(doto (unrepl-reader rdr)
(.setCoords {:file name})))))
(defn soft-store [make-action]
(let [ids-to-session+refs (atom {})
refs-to-ids (atom {})
refq (java.lang.ref.ReferenceQueue.)
NULL (Object.)]
(.start (Thread. (fn []
(let [ref (.remove refq)]
(let [id (@refs-to-ids ref)]
(swap! refs-to-ids dissoc ref)
(swap! ids-to-session+refs dissoc id)))
(recur))))
{:put (fn [session-id x]
(let [x (if (nil? x) NULL x)
id (keyword (gensym))
ref (java.lang.ref.SoftReference. x refq)]
(swap! refs-to-ids assoc ref id)
(swap! ids-to-session+refs assoc id [session-id ref])
{:get (make-action id)}))
:get (fn [id]
(when-some [[session-id ^java.lang.ref.Reference r] (@ids-to-session+refs id)]
(let [x (.get r)]
[session-id (if (= NULL x) nil x)])))}))
(defonce ^:private sessions (atom {}))
(defn session [id]
(some-> @sessions (get id) deref))
(defonce ^:private elision-store (soft-store #(list `fetch %)))
(defn fetch [id]
(if-some [[session-id x] ((:get elision-store) id)]
(unrepl.printer$Z4aBFi34iyy94VC1WUZQs1VjhAE.WithBindings.
(select-keys (some-> session-id session :bindings) [#'*print-length* #'*print-level* #'unrepl/*string-length* #'p/*elide*])
(cond
(instance? unrepl.printer$Z4aBFi34iyy94VC1WUZQs1VjhAE.ElidedKVs x) x
(string? x) x
(instance? unrepl.printer$Z4aBFi34iyy94VC1WUZQs1VjhAE.MimeContent x) x
:else (seq x)))
p/unreachable))
(defn interrupt! [session-id eval]
(let [{:keys [^Thread thread eval-id promise]}
(some-> session-id session :current-eval)]
(when (and (= eval eval-id)
(deliver promise
{:ex (doto (ex-info "Evaluation interrupted" {::phase :eval})
(.setStackTrace (.getStackTrace thread)))
:bindings {}}))
(.stop thread)
true)))
(defn background! [session-id eval]
(let [{:keys [eval-id promise future]}
(some-> session-id session :current-eval)]
(boolean
(and
(= eval eval-id)
(deliver promise
{:eval future
:bindings {}})))))
(defn attach-sideloader! [session-id]
(prn '[:unrepl.jvm.side-loader/hello])
(some-> session-id session :side-loader
(reset!
(let [out *out*
in *in*]
(fn self [k name]
(binding [*out* out]
(locking self
(prn [k name])
(some-> (edn/read {:eof nil} in) p/base64-decode)))))))
(let [o (Object.)] (locking o (.wait o))))
(defn enqueue [session-id f]
(some-> session-id session ^java.util.concurrent.BlockingQueue (:actions-queue) (.put f)))
(defn set-file-line-col [session-id file line col]
(enqueue session-id #(when-some [in (some-> session-id session :in)]
(set! *file* file)
(set! *source-path* file)
(.setCoords ^ILocatedReader in {:line line :col col :file file}))))
(def schedule-flushes!
(let [thread-pool (java.util.concurrent.Executors/newScheduledThreadPool 1)
max-latency-ms 20]
(fn [w]
(let [wr (java.lang.ref.WeakReference. w)
vfut (volatile! nil)]
(vreset! vfut
(.scheduleAtFixedRate
thread-pool
(fn []
(if-some [^java.io.Writer w (.get wr)]
(.flush w)
(.cancel ^java.util.concurrent.Future @vfut)))
max-latency-ms max-latency-ms java.util.concurrent.TimeUnit/MILLISECONDS))))))
(defn scheduled-writer [& args]
(-> (apply tagging-writer args)
java.io.BufferedWriter.
(doto schedule-flushes!)))
(defmacro ^:private flushing [bindings & body]
`(binding ~bindings
(try ~@body
(finally ~@(for [v (take-nth 2 bindings)]
`(.flush ~(vary-meta v assoc :tag 'java.io.Writer)))))))
(def ^:dynamic eval-id)
(def ^:dynamic interrupted? (constantly false))
(defn seek-readable
"Skips whitespace and comments on stream s. Returns true when a form may be read,
  false otherwise.
  Note that returning true does not guarantee that the next read will yield something.
  (It may be EOF, or a discard #_ or a non-matching conditional...)"
[s]
(loop [comment false]
(let [c (.read s)]
(cond
(interrupted?) (do (.unread s c) false)
(= c (int \newline)) false
comment (recur comment)
(= c -1) true
(= c (int \;)) (recur true)
(or (Character/isWhitespace (char c)) (= c (int \,))) (recur comment)
:else (do (.unread s c) true)))))
(defn unrepl-read [request-prompt request-exit]
(blame :read
(if (seek-readable *in*)
(let [coords (:coords *in*)]
(try
(read {:read-cond :allow :eof request-exit} *in*)
(finally
(let [coords' (:coords *in*)]
(unrepl/write [:read {:file (:file coords)
:from [(:line coords) (:col coords)] :to [(:line coords') (:col coords')]
:offset (:offset coords)
:len (- (:offset coords') (:offset coords))}
eval-id])))))
request-prompt)))
(defn start [ext-session-actions]
(with-local-vars [prompt-vars #{#'*ns* #'*warn-on-reflection*}
current-eval-future nil]
(let [ext-session-actions
(into {}
(map (fn [[k v]]
[k (if (and (seq? v) (symbol? (first v)) (namespace (first v)))
(list `ensure-ns v)
v)]))
ext-session-actions)
session-id (keyword (gensym "session"))
raw-out *out*
in (ensure-unrepl-reader *in* (str "unrepl-" (name session-id)))
actions-queue (java.util.concurrent.LinkedBlockingQueue.)
session-state (atom {:current-eval {}
:in in
:log-eval (fn [msg]
(when (bound? eval-id)
(unrepl/write [:log msg eval-id])))
:log-all (fn [msg]
(unrepl/write [:log msg nil]))
:side-loader (atom nil)
:prompt-vars #{#'*ns* #'*warn-on-reflection*}
:actions-queue actions-queue})
current-eval-thread+promise (atom nil)
say-hello
(fn []
(unrepl/non-eliding-write
[:unrepl/hello {:session session-id
:actions (into
{:start-aux `(start-aux ~session-id)
:log-eval
`(some-> ~session-id session :log-eval)
:log-all
`(some-> ~session-id session :log-all)
:print-limits
`(let [bak# {:unrepl.print/string-length unrepl/*string-length*
:unrepl.print/coll-length *print-length*
:unrepl.print/nesting-depth *print-level*}]
(some->> ~(tagged-literal 'unrepl/param :unrepl.print/string-length) (set! unrepl/*string-length*))
(some->> ~(tagged-literal 'unrepl/param :unrepl.print/coll-length) (set! *print-length*))
(some->> ~(tagged-literal 'unrepl/param :unrepl.print/nesting-depth) (set! *print-level*))
bak#)
:set-source
`(set-file-line-col ~session-id
~(tagged-literal 'unrepl/param :unrepl/sourcename)
~(tagged-literal 'unrepl/param :unrepl/line)
~(tagged-literal 'unrepl/param :unrepl/column))
:unrepl.jvm/start-side-loader
`(attach-sideloader! ~session-id)}
ext-session-actions)}]))
interruptible-eval
(fn [form]
(try
(let [original-bindings (get-thread-bindings)
p (promise)
f
(future
(swap! session-state update :current-eval
assoc :thread (Thread/currentThread))
(with-bindings original-bindings
(try
(unrepl/non-eliding-write
[:started-eval
{:actions
{:interrupt (list `interrupt! session-id eval-id)
:background (list `background! session-id eval-id)}}
eval-id])
(let [v (blame :eval (eval form))]
(deliver p {:eval v :bindings (get-thread-bindings)})
v)
(catch Throwable t
(deliver p {:ex t :bindings (get-thread-bindings)})
(throw t)))))]
(swap! session-state update :current-eval
into {:eval-id eval-id :promise p :future f})
(let [{:keys [ex eval bindings]} @p]
(swap! session-state assoc :bindings bindings)
(doseq [[var val] bindings
:when (not (identical? val (original-bindings var)))]
(var-set var val))
(if ex
(throw ex)
eval)))
(finally
(swap! session-state assoc :current-eval {}))))
cl (.getContextClassLoader (Thread/currentThread))
slcl (classloader cl
(fn [k x]
(when-some [f (some-> session-state deref :side-loader deref)]
(f k x))))]
(swap! session-state assoc :class-loader slcl)
(swap! sessions assoc session-id session-state)
(binding [*out* (scheduled-writer :out unrepl/non-eliding-write)
*err* (tagging-writer :err unrepl/non-eliding-write)
*in* in
*file* (-> in :coords :file)
*source-path* *file*
*default-data-reader-fn* tagged-literal
p/*elide* (partial (:put elision-store) session-id)
unrepl/*string-length* unrepl/*string-length*
unrepl/write (atomic-write raw-out)
unrepl/read unrepl-read
eval-id 0
interrupted? #(.peek actions-queue)]
(.setContextClassLoader (Thread/currentThread) slcl)
(with-bindings {clojure.lang.Compiler/LOADER slcl}
(try
(m/repl
:init #(do
(swap! session-state assoc :bindings (get-thread-bindings))
(say-hello))
:need-prompt (constantly true)
:prompt (fn []
(when-some [f (.poll actions-queue)] (f))
(unrepl/non-eliding-write [:prompt (into {:file *file*
:line (.getLineNumber *in*)
:column (.getColumnNumber *in*)
:offset (:offset *in*)}
(map (fn [v]
(let [m (meta v)]
[(symbol (name (ns-name (:ns m))) (name (:name m))) @v])))
(:prompt-vars @session-state))
(set! eval-id (inc eval-id))]))
:read unrepl/read
:eval (fn [form]
(flushing [*err* (tagging-writer :err eval-id unrepl/non-eliding-write)
*out* (scheduled-writer :out eval-id unrepl/non-eliding-write)]
(interruptible-eval form)))
:print (fn [x]
(unrepl/write [:eval x eval-id]))
:caught (fn [e]
(let [{:keys [::ex ::phase]
:or {ex e phase :repl}} (ex-data e)]
(unrepl/write [:exception {:ex ex :phase phase} eval-id]))))
(finally
(.setContextClassLoader (Thread/currentThread) cl))))))))
(defn start-aux [session-id]
(let [cl (.getContextClassLoader (Thread/currentThread))]
(try
(some->> session-id session :class-loader (.setContextClassLoader (Thread/currentThread)))
(start {})
(finally
(.setContextClassLoader (Thread/currentThread) cl)))))
(defmacro ensure-ns [[fully-qualified-var-name & args :as expr]]
`(do
(require '~(symbol (namespace fully-qualified-var-name)))
~expr))
<<<FIN
(clojure.core/ns user)
(unrepl.repl$gOQFDUuzHjLIF1eKBU9gtZ8I4Xk/start (clojure.edn/read {:default tagged-literal} *in*))
{:complete (unrepl.actions.complete$rH_N_7nQsmkfOrRjLdk$fEUUS6o/complete #unrepl/param :unrepl.complete/before #unrepl/param :unrepl.complete/after #unrepl/param :unrepl.complete/ns)}
