(ns iclj.messages
  (:require
   [cheshire.core :as cheshire]
   [clj-time.core :as time]
   [clj-time.format :as time-format]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [pandect.algo.sha256 :refer [sha256-hmac]]
   [taoensso.timbre :as log]
   [zeromq.zmq :as zmq]))

(def protocol-version "5.0")

(defn uuid [] (str (java.util.UUID/randomUUID)))

(defn now []
  "Returns current ISO 8601 compliant date."
  (let [current-date-time (time/to-time-zone (time/now) (time/default-time-zone))]
    (time-format/unparse
     (time-format/with-zone (time-format/formatters :date-time-no-ms)
       (.getZone current-date-time))
     current-date-time)))

(defn message-header [message msgtype]
  (cheshire/generate-string
   {:msg_id (uuid)
    :date (now)
    :username (get-in message [:header :username])
    :session (get-in message [:header :session])
    :msg_type msgtype
    :version protocol-version}))

(defn new-header [msg_type session-id]
  {:date (now)
   :version protocol-version
   :msg_id (uuid)
   :username "kernel"
   :session session-id
   :msg_type msg_type})

(defn send-message
  ([socket msg-type content parent-header session-id metadata key]
    (send-message socket msg-type content parent-header session-id metadata key [(.getBytes ^String msg-type)]))
  ([socket msg-type content parent-header session-id metadata key idents]
    (let [header        (cheshire/generate-string (new-header msg-type session-id))
          parent-header (cheshire/generate-string parent-header)
          metadata      (cheshire/generate-string metadata)
          content       (cheshire/generate-string content)]
     (doseq [ident idents]
       (zmq/send socket ident zmq/send-more))
     (doseq [^String part ["<IDS|MSG>"
                           (if (empty? key) "" (sha256-hmac (str header parent-header metadata content) key))
                           header
                           parent-header
                           metadata]]
       (zmq/send socket (.getBytes part) zmq/send-more))
     (zmq/send socket (.getBytes ^String content)))))

(defn parse-message [message]
  {:idents (:idents message)
   :delimiter (:delimiter message)
   :signature (:signature message)
   :header (cheshire/parse-string (:header message) keyword)
   :parent-header (cheshire/parse-string (:parent-header message) keyword)
   :content (cheshire/parse-string (:content message) keyword)})

;; Message contents

(defn pyin-content [execution-count message]
  {:execution_count execution-count
   :code (get-in message [:content :code])})

(defn complete-reply-content
  [nrepl-comm
   message]
  (let [{:keys [code cursor_pos]} (:content message)
        left (subs code 0 cursor_pos)
        right (subs code cursor_pos)]
    {#_#_:matches (pnrepl/nrepl-complete nrepl-comm [left right])
     :cursor_start cursor_pos #_(- cursor_pos (count sym)) ; TODO fix
     :cursor_end cursor_pos
     :status "ok"}))

(defn kernel-info-content []
  {:status "ok"
   :protocol_version protocol-version
   :implementation "iclojure"
   :language_info {:name "clojure"
                   :version (clojure-version)
                   :mimetype "text/x-clojure"
                   :file_extension ".clj"}
   :banner "IClojure-0.1.0"
   :help_links []})

(defn comm-open-reply-content [message]
  {:comm_id (get-in message [:content :comm_id])
   :data {}})

;; Request and reply messages

(defn input-request
  [sockets parent-header session-id key idents]
  (let [metadata {}
        content  {:prompt ">> "
                  :password false}]
    (send-message (:stdin-socket sockets)
                         "input_request"
                         content parent-header session-id metadata key idents)))

(defn comm-open-reply
  [sockets
   socket message key]
  "Just close a comm immediately since we don't handle it yet"
  (let [parent-header (:header message)
        session-id (get-in message [:header :session])
        idents (:idents message)
        metadata {}
        content  (comm-open-reply-content message)]
    (send-message (sockets socket)
                         "comm_close"
                         content parent-header session-id metadata key idents)))

;; Handlers

(defn execute-request [alive broadcast execution-count nrepl-comm socket message key]
  (let [session-id (get-in message [:header :session])
        idents (:idents message)
        parent-header (:header message)
        code (get-in message [:content :code])
        silent (str/ends-with? code ";")]
    (broadcast "execute_input" (pyin-content execution-count message))
    #_(let [nrepl-resp (pnrepl/nrepl-eval nrepl-comm alive sockets
                                          code parent-header
                                          session-id key idents)
            {:keys [result ename traceback]} nrepl-resp
            error (if ename
                    {:status "error"
                     :ename ename
                     :evalue ""
                     :execution_count @execution-count
                     :traceback traceback})]
        (send-message (:shell-socket sockets) "execute_reply"
          (if error
            error
            {:status "ok"
             :execution_count @execution-count
             :user_expressions {}})
          parent-header
          session-id
          {}
          key idents)
        (cond
          error
          (send-message (:iopub-socket sockets) "error"
                        (dissoc error :status :execution_count) parent-header session-id {} key)
          (not (or (= result "nil") silent))
          (send-message (:iopub-socket sockets) "execute_result"
                        {:execution_count @execution-count
                         :data (cheshire/parse-string result true)
                         :metadata {}}
                        parent-header session-id {} key))
        (swap! execution-count inc))))
