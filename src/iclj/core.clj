(ns iclj.core
  (:require [beckon]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.edn :as edn]
    [iclj.unrepl :as unrepl]
    [iclj.unrepl.elisions :as elisions]
    [iclj.messages :as msg]
    [iclj.print.html :as li]
    [cheshire.core :as json]
    [clojure.stacktrace :as st]
    [clojure.walk :as walk]
    [clojure.core.async :as a]
    [clojure.string :as str]
    [taoensso.timbre :as log]
    [zeromq.zmq :as zmq]
    [net.cgrand.packed-printer :as pp]
    [net.cgrand.xforms :as x]
    [clojure.tools.deps.alpha :as deps])
  (:import [java.net ServerSocket])
  (:gen-class :main true))

(defn prep-config [args]
  (-> args
      first
      slurp
      (json/parse-string keyword)))

(defn exception-handler [e]
  (log/error (with-out-str (st/print-stack-trace e 20))))

(defn parts-to-message [parts]
  (let [delim "<IDS|MSG>"
        delim-bytes (.getBytes delim "UTF-8")
        [idents [_ & more-parts]] (split-with #(not (java.util.Arrays/equals delim-bytes ^bytes %)) parts)
        blobs (map #(new String % "UTF-8") more-parts)
        blob-names [:signature :header :parent-header :metadata :content]
        message (merge
                 {:idents idents :delimiter delim}
                 (zipmap blob-names blobs)
                 {:buffers (drop (count blob-names) blobs)})]
    message))

(defmacro ^:private while-some [binding & body]
  `(loop []
     (when-some ~binding
       ~@body
       (recur))))

(def zmq-out
  (let [ch (a/chan)]
    (a/thread
      (while-some [args (a/<!! ch)]
        (try
          (apply msg/send-message args)
          (catch Exception e
            (prn 'FAIL e args)))))
    ch))

(defn zmq-ch [socket]
  (let [ch (a/chan)]
    (a/thread
      (try
        (while (->> socket zmq/receive-all parts-to-message msg/parse-message (a/>!! ch)))
        (catch Exception e
          (exception-handler e))
        (finally
          (zmq/set-linger socket 0)
          (zmq/close socket))))
    ch))

(defn heartbeat-loop [alive hb-socket]
  (a/thread
    (try
      (while @alive
        (zmq/send hb-socket (zmq/receive hb-socket)))
      (catch Exception e
        (exception-handler e))
      (finally
        (zmq/set-linger hb-socket 0)
        (zmq/close hb-socket)))))

(defn address [config service]
  (str (:transport config) "://" (:ip config) ":" (service config)))

(defn aux-eval [{:keys [in]} form]
  (let [edn-out (a/chan 1 (filter (fn [[tag payload id]] (case tag (:eval :exception) true false))))]
    (a/go
      (when (some-> in (a/>! [(prn-str form) edn-out]))
        (a/<! edn-out)))))

(defn elision? [x]
  (and (tagged-literal? x) (= 'unrepl/... (:tag x))))

(defn elision-expand-1 [aux x]
  (loop [aux aux x x]
    (cond
      (elision? x)
      (if-some [form (-> x :form :get)]
        (let [[tag payload] (a/<!! (aux-eval aux form))]
          (case tag
            :eval (recur aux payload)
            :exception (throw (ex-info "Error while resolving elision." {:ex payload}))))
        (throw (ex-info "Unresolvable elision" {})))
      
      (map? x)
      (if-some [e (get x (tagged-literal 'unrepl/... nil))]
        (-> x (dissoc (tagged-literal 'unrepl/... nil))
          (into (elision-expand-1 aux e)))
        x)
      
      (vector? x)
      (if-let [last (when-some [last (peek x)]
                     (and (elision? last) last))]
       (recur aux (into (pop x) (elision-expand-1 aux last)))
       x)
    
      (seq? x)
      (lazy-seq
        (when-some [s (seq x)]
          (let [x (first s)]
            (if (elision? x)
              (elision-expand-1 aux x)
              (cons x (elision-expand-1 aux (rest s)))))))
      
      :else x)))

(defn elision-expand-all [aux x]
  (walk/prewalk
    (fn [x]
      (let [x (elision-expand-1 aux x)]
        (if (and (tagged-literal? x) (not (elision? x)))
          (tagged-literal (:tag x) (elision-expand-all aux (:form x)))
          x)))
    x))

(defn is-complete?
  "Returns whether or not what the user has typed is complete (ready for execution).
   Not yet implemented. May be that it is just used by jupyter-console."
  [code]
  (try
    (or (re-matches #"\s*/(\S+)\s*(.*)" code) ; /command
      (read-string code))
    true
    (catch Exception _
      false)))

(defn handle-prompt [state payload]
  (when-some [ns (get payload 'clojure.core/*ns*)]
    (swap! state assoc :ns (:form ns))))

(defn framed-eval-process [code ctx state]
  (let [{:keys [execution-count repl aux]} (swap! state update :execution-count inc)
        edn-out (a/chan)]
    (a/go
      (a/>! ctx [:broadcast "execute_input" {:execution_count execution-count :code code}])
      (a/>! (:in repl) [code edn-out])
      (loop [done false]
        (if-some [[tag payload id :as msg] (a/<! edn-out)]
          (do (prn 'GOT2 msg)
            (case tag
             :prompt (do ; should not occur: prompts have no id
                       (handle-prompt state payload)
                       (when-not done (recur done)))
             :started-eval (do (swap! state assoc :interrupt-form (-> payload :actions :interrupt)) (recur done))
             :eval (let [extra-reps (when-some [{:keys [content-type content]} 
                                                (and (tagged-literal? payload)
                                                  (case (:tag payload)
                                                    unrepl/object (-> payload :form (nth 2) :attachment :form)
                                                    unrepl/mime (:form payload)
                                                    nil))]
                                      (let [content (elision-expand-all aux content)
                                            content (if (and (tagged-literal? content) (= 'unrepl/base64 (:tag content)))
                                                      (:form content)
                                                      content)] 
                                        (prn 'MIME content-type content)
                                        {(or content-type "application/octet-stream") content}))]
                     (a/>! ctx [:broadcast "execute_result"
                                {:execution_count execution-count
                                 :data (into {:text/plain (with-out-str (pp/pprint payload :as :unrepl/edn :strict 20 :width 72))
                                              :text/iclojure-html (li/html payload)}
                                         extra-reps)
                                 :metadata {}
                                 :transient {}}])
                     (a/>! ctx [:reply {:status "ok"
                                        :execution_count execution-count
                                        :user_expressions {}}])
                     (recur true))
             :exception (let [error
                              {:status "error"
                               :ename "Oops"
                               :evalue ""
                               :execution_count execution-count
                               :traceback (let [{:keys [ex phase]} payload]
                                            [(str "Exception while " (case phase :read "reading the expression" :eval "evaluating the expression"
                                                                       :print "printing the result" "doing something unexpected") ".")
                                             (with-out-str (pp/pprint ex :as :unrepl/edn :strict 20 :width 72))])}]
                          (a/>! ctx [:broadcast "error" (dissoc error :status :execution_count)])
                          (a/>! ctx [:reply error])
                          (recur true))

             (:out :err)
             (do
               (a/>! ctx [:broadcast "stream" {:name (case tag :out "stdout" :err "stderr")
                                               :text payload}])
               (recur done))

             (recur done)))
         (throw (ex-info "edn output from unrepl unexpectedly closed; the connection to the repl has probably been interrupted.")))))))

(defn action-call [form args-map]
  (walk/prewalk (fn [x]
                  (if (and (tagged-literal? x) (= 'unrepl/param (:tag x)))
                    (args-map (:form x))
                    x))
    form))

(defn- self-connector []
  (let [{in-writer :writer in-reader :reader} (unrepl/pipe)
        {out-writer :writer out-reader :reader} (unrepl/pipe)]
    (a/thread
      (binding [*out* out-writer *in* (clojure.lang.LineNumberingPushbackReader. in-reader)]
        (clojure.main/repl)))
    {:in in-writer
     :out out-reader}))

(defn- connect [state connector]
  (let [repl-in (a/chan)
        repl-out (a/chan)]
    (swap! state assoc :connector connector :repl nil :aux nil :class-loader nil)
    (unrepl/unrepl-process (unrepl/unrepl-connect connector) repl-in repl-out)
    (swap! state assoc :repl {:in repl-in :out repl-out})
    (a/go
      (while-some [[tag payload id] (a/<! repl-out)]
        (case tag
          :unrepl/hello
          (let [{:keys [start-aux :unrepl.jvm/start-side-loader] :as actions} (:actions payload)
                aux-in (a/chan)
                aux-out (a/chan)]
            (prn 'GOTHELLO)
            (swap! state assoc :actions actions)
            (when start-aux
              (prn 'STARTAUX)
              (unrepl/unrepl-process (unrepl/aux-connect connector start-aux) aux-in aux-out)
              (swap! state assoc :aux {:in aux-in :out aux-out})
              (a/go
                (prn 'STARTEDAUX)
                (while-some [[tag payload id] (a/<! aux-out)]
                  (prn 'AUX-DROPPED [tag payload id]))))
            (when start-side-loader
              (prn 'STARTSIDELOADER)
              (let [class-loader (clojure.lang.DynamicClassLoader. (.getContextClassLoader (Thread/currentThread)))
                    {:keys [^java.io.Writer in ^java.io.Reader out]} (connector)]
                (binding [*out* in] (prn start-side-loader)) ; send upgrade form
                (unrepl/sideloader-loop in out class-loader)
                (swap! state assoc :class-loader class-loader))))
          
          :prompt (handle-prompt state payload)
          
          (prn 'DROPPED [tag payload id]))))))

(defn run-kernel [config]
  (let [hb-addr      (address config :hb_port)
       shell-addr   (address config :shell_port)
       iopub-addr   (address config :iopub_port)
       control-addr (address config :control_port)
       stdin-addr   (address config :stdin_port)
       key          (:key config)]
   (let [alive  (atom true)
         context (zmq/context 1)
         shell-socket (doto (zmq/socket context :router) (zmq/bind shell-addr))
         shell (zmq-ch shell-socket)
         control-socket (doto (zmq/socket context :router) (zmq/bind control-addr))
         control (zmq-ch control-socket)
         iopub-socket (doto (zmq/socket context :pub) (zmq/bind iopub-addr))
         stdin-socket (doto (zmq/socket context :router) (zmq/bind stdin-addr))
         stdin (zmq-ch stdin-socket)
         status-sleep 1000
         state (doto
                 (atom {:execution-count 1
                       :repl nil
                       :aux nil
                       :interrupt-form nil
                       :actions nil
                       :comm-targets {}})
                 (connect self-connector))
         msg-context
         (fn [socket {{msg-type :msg_type session :session :as header} :header idents :idents :as request}]
           (let [ctx (a/chan)
                 zmq-msg (fn [[tag arg1 arg2]]
                           (case tag
                             :reply
                             (let [content arg1
                                   metadata (or arg2 {})
                                   [_ msg-prefix] (re-matches #"(.*)_request" msg-type)]
                               [socket (str msg-prefix "_reply")
                                content header session metadata key idents])
                             :broadcast
                             (let [msg-type arg1, content arg2]
                               [iopub-socket msg-type content header session {} key])))]
             (a/go
               (a/>! zmq-out (zmq-msg [:broadcast "status" {:execution_state "busy"}]))
               (while-some [msg (a/<! ctx)]
                 (a/>! zmq-out (zmq-msg msg)))
               (a/>! zmq-out (zmq-msg [:broadcast "status" {:execution_state "idle"}])))
             ctx))
         shell-handler
         (fn [socket]
           (let [msgs-ch (a/chan)]
             (a/go-loop []
               (when-some [{{msg-type :msg_type session :session :as header} :header idents :idents :as request} (a/<! msgs-ch)]
                 (let [ctx (msg-context socket request)]
                   (try
                     (case msg-type
                       "execute_request"
                       (let [code (get-in request [:content :code])
                             silent (str/ends-with? code ";")
                             [_ command args] (re-matches #"(?s)\s*/(\S+?)([\s,\[{(].*)?" code)
                             elided (some-> command elisions/lookup :form :get)]
                         (if (or (nil? command) elided)
                           (framed-eval-process (prn-str (or elided `(binding [*data-readers* (assoc *data-readers* '~'unrepl/mime (fn [form#] `(tagged-literal '~'~'unrepl/mime ~form#)))] (eval (read-string ~code))))) ctx state)
                           (let [{:keys [execution-count]} (swap! state update :execution-count inc)]
                             (case command
                               "connect" (let [args (re-seq #"\S+" args)]
                                           (try
                                             (let [[_ host port inner] (re-matches #"(?:(?:(\S+):)?(\d+)|(-))" (first args))]
                                               (connect state (if inner
                                                                self-connector
                                                                #(let [socket (java.net.Socket. ^String host (Integer/parseInt port))]
                                                                   {:in (-> socket .getOutputStream io/writer)
                                                                    :out (-> socket .getInputStream io/reader)})))
                                               (a/>! ctx [:broadcast "stream" {:name "stdout" :text "Successfully connected!"}]))
                                             (catch Exception e
                                               (a/>! ctx [:broadcast "stream" {:name "stderr" :text "Failed connection."}])))
                                           (a/>! ctx [:reply {:status "ok"
                                                              :execution_count execution-count
                                                              :user_expressions {}}]))

                               "cp" (try
                                      (let [arg (edn/read-string args)
                                            arg (if (seq? arg)
                                                  (let [{:keys [ns aux]} @state
                                                        [tag payload] (a/<! (aux-eval aux `(do (in-ns '~ns) ~arg)))]
                                                    (case tag
                                                      :eval (elision-expand-all aux payload)
                                                      :exception (throw (ex-info "Exception on aux." {:form arg :payload payload}))))
                                                  arg)
                                            paths
                                            (cond
                                              (map? arg)
                                              (let [deps (if (every? symbol? (keys arg))
                                                           {:deps arg}
                                                           arg)
                                                    libs (deps/resolve-deps deps {})]
                                                (into [] (mapcat :paths) (vals libs)))
                                              
                                              (string? arg) [arg]
                                              :else (throw (IllegalArgumentException. (str "Unsupported /cp argument: " arg))))]
                                        (doseq [path paths]
                                          (.addURL ^clojure.lang.DynamicClassLoader (:class-loader @state) (-> path java.io.File. .toURI .toURL)))
                                        (a/>! ctx [:broadcast "stream" {:name "stdout" :text (str paths " added to the classpath!")}])
                                        {:result "nil"})
                                      (catch Exception e
                                        (prn 'FAIL e)
                                        (a/>! ctx [:broadcast "stream" {:name "stderr" :text "Something unexpected happened."}])
                                        {:result "nil"}))
                               
                              (do ; default
                                (a/>! ctx [:broadcast "stream" {:name "stderr" :text (str "Unknown command: /" command ".")}])
                                (a/>! ctx [:reply {:status "ok"
                                                   :execution_count execution-count
                                                   :user_expressions {}}]))))))
                       
                       "kernel_info_request"
                       (a/>! ctx [:reply (msg/kernel-info-content)])

                       "shutdown_request"
                       (do
                         (reset! alive false)
                         #_(nrepl.server/stop-server server)
                         (a/>! ctx [:reply {:status "ok" :restart false}])
                         (Thread/sleep 100)) ; magic timeout! TODO fix
                       
                      "comm_open"
                      (let [{:keys [comm-targets]} @state
                            {:keys [comm_id target_name data]} (:content request)]
                        (prn request (comm-targets target_name))
                        (if-some [comm-target (comm-targets target_name)]
                          (let [comm (comm-target comm_id data)]
                            (swap! state update :comms assoc comm_id [target_name comm]))
                          (a/>! ctx [:broadcast "comm_close" {:comm_id comm_id :data {}}])))
                      
                      "comm_msg"
                      (let [{:keys [comms]} @state
                            {:keys [comm_id data]} (:content request)]
                        (prn request :comm (comms comm_id))
                        (when-not (some-> (comms comm_id) second (a/>! [data ctx]))
                          ; unknown comm or closed channel
                          (a/>! ctx [:broadcast "comm_close" {:comm_id comm_id :data {}}])))

                      "comm_close"
                      (let [{:keys [comms]} @state
                            {:keys [comm_id data]} (:content request)]
                        (prn request)
                        (some-> (comms comm_id) second a/close!)
                        (swap! state update :comms dissoc comm_id))
                      
                      "comm_info_request"
                      (let [{:keys [comms]} @state
                            {:keys [target_name]} (:content request)]
                        (prn request)
                        (a/>! ctx [:reply {:comms (into {} (x/by-key
                                                             (x/for [[target] %
                                                                     :when (or (nil? target_name) (= target_name target))]
                                                               {:target_name target})) comms)}]))
              
                      "is_complete_request"
                      (a/>! ctx [:reply {:status (if (-> request :content :code is-complete?) "complete" "incomplete")}])

                      "complete_request"
                      (let [{{:keys [complete]} :actions aux :aux ns :ns} @state
                            {:keys [code cursor_pos]} (:content request)
                            left (subs code 0 cursor_pos)
                            right (subs code cursor_pos)
                            [tag payload] (a/<! (aux-eval aux (action-call complete {:unrepl.complete/ns (list 'quote ns)
                                                                                     :unrepl.complete/before left
                                                                                     :unrepl.complete/after right})))
                            payload (elision-expand-all aux (case tag :eval payload nil))
                            max-left-del (transduce (map :left-del) max 0 payload)
                            max-right-del (transduce (map :right-del) max 0 payload)
                            candidates (map 
                                         (fn [{:keys [candidate left-del right-del]}]
                                           (str (subs left (- cursor_pos (- max-left-del left-del)))
                                             candidate
                                             (subs right right-del max-right-del)))
                                         payload)]
                        (a/>! ctx
                          [:reply
                           {:matches candidates
                            :cursor_start (- cursor_pos max-left-del)
                            :cursor_end (+ cursor_pos max-right-del)
                            :status "ok"}]))
                      
                      "interrupt_request"
                      (let [{aux :aux :keys [interrupt-form]} @state]
                        (a/<! (aux-eval aux interrupt-form)))
                      
                      (do
                        (log/error "Message type" msg-type "not handled yet. Exiting.")
                        (log/error "Message dump:" request)
                        (System/exit -1)))
                     (finally
                       (a/>! ctx [:broadcast "status" {:execution_state "idle"}]))))
               (recur)))
             msgs-ch))
         shell-process (shell-handler shell-socket)
         control-process (shell-handler control-socket)]
     
     (swap! state assoc-in [:comm-targets "expansion"]
       (fn [comm-id _]
         (let [msg-in (a/chan)]
           (a/go-loop []
             (when-some [[{:keys [elision-id]} ctx] (a/<! msg-in)]
               (prn 'elision-id elision-id)
               (when-some [aux (:aux @state)]
                 (prn 'going-to-resolve)
                 (let [form (read-string elision-id)
                       [tag payload] (a/<! (aux-eval aux form))]
                   (prn 'expansion tag payload)
                   (case tag
                     :eval (let [payload
                                 (cond
                                   (nil? payload) ()
                                   (= payload (tagged-literal 'unrepl/... nil)) (list payload)
                                   :else payload)]
                             (a/>! ctx [:broadcast "comm_msg"
                                        {:comm_id comm-id
                                         :data {:elision-id elision-id
                                                :expansion (li/html payload)}}]))
                     :exception (throw (ex-info "Error while resolving elision." {:ex payload})))
                   (recur)))))
           msg-in)))
     
     (heartbeat-loop alive (doto (zmq/socket context :rep) (zmq/bind hb-addr)))
      
     (a/go-loop [state {}]
       (a/alt!
         shell ([request] (prn 'SHELL) (a/>! shell-process request))
         control ([request] (prn 'CONTROL) (a/>! control-process request))
         #_#_iopub 
         ([{{msg-type :msg_type} :header :as request}]
           (case msg-type
             #_#_"input_reply" TODO
                            
             (do
               (log/error "Message type" msg-type "not handled yet. Exiting.")
               (log/error "Message dump:" message)
               (System/exit -1)))))
       (recur state)))))

(defn -main [& args]
  (log/set-level! :error)
  (run-kernel (prep-config args)))

