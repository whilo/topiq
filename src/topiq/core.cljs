(ns topiq.core
  (:require [cljs.core.async :refer [put! chan <! >! alts! timeout close!] :as async]
            [cljs.reader :refer [read-string] :as read]
            [clojure.data :refer [diff]]
            [clojure.set :as set]
            [datascript.core :as d]
            [hasch.core :refer [uuid]]
            [kabel.middleware.block-detector :refer [block-detector]]
            [kioo.core :refer [handle-wrapper]]
            [kioo.om :refer [content set-attr do-> substitute listen]]
            [konserve.core :as k]
            [konserve.memory :refer [new-mem-store]]
            [om.core :as om :include-macros true]
            [om.dom :as omdom]
            [replikativ.crdt.cdvcs.realize :refer [commit-history-values trans-apply summarize-conflict commit-history]]
            [replikativ.crdt.cdvcs.stage :as sc]
            [replikativ.crdt.materialize :refer [key->crdt]]
            [replikativ.crdt.cdvcs.realize :refer [head-value]]
            [replikativ.crdt :refer [map->CDVCS]]
            [replikativ.p2p.fetch :refer [fetch]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [replikativ.p2p.hooks :refer [hook default-integrity-fn]]
            [replikativ.p2p.filter-subs :refer [filtered-subscriptions]]
            [replikativ.peer :refer [client-peer]]
            [replikativ.protocols :refer [-downstream]]
            [replikativ.stage :as s]
            [topiq.view :refer [topiqs navbar topiq-arguments]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(enable-console-print!)

(def err-ch (chan))

;; Let errors pop up
(go-loop [e (<! err-ch)]
  (when e
    (.error js/console "UNCAUGHT:" e)
    (js/alert (str "Ooops: " e))
    (recur (<! err-ch))))

(def uri (goog.Uri. js/location.href))

(def ssl? (= (.getScheme uri) "https"))


(def eval-fn {'(fn [_ new] new) (fn [_ new] new)
              '(fn [old params] (d/db-with old params))
              (fn [old params]
                ;; HACK let's ensure that there always is a db
                (let [old (if-not (d/db? old)
                            (let [schema {:identity/id {:db/unique :db.unique/identity}}
                                  conn   (d/create-conn schema)]
                              @conn)
                            old)]
                  (d/db-with old params)))})


(defn navbar-view
  "Builds navbar with search, user menu and user-related modals"
  [login-fn app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:current-user "Not logged in"
       :search-placeholder "Search..."
       :search-text ""
       :login-user-text ""
       :login-fn login-fn})
    om/IRenderState
    (render-state [this {:keys [current-user search-text login-user-text search-placeholder] :as state}]
      (navbar
       owner
       state))))

(def val-atom (atom {}))

;; refresh time calculations


(defn topiqs-view
  "Builds topiqs list with topiq head and related argument list, resolves conflicts"
  [stage app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:selected-topiq false
       :stage stage
       :refresh-loop (go-loop []
                       (<! (timeout (* 60 1000)))
                       ;; do not eat the battery
                       (when-not (.-hidden js/document)
                         (om/refresh! owner))
                       (recur))})
    om/IRenderState
    (render-state [this {:keys [selected-topiq stage] :as state}]
      (let [user (get-in @stage [:config :user])
            val (om/value app)]
        (cond (= (type val) replikativ.crdt.cdvcs.realize/Conflict) ;; TODO implement with protocol dispatch
              (do
                (sc/merge! stage [user #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"] (:heads val))
                (omdom/h3 nil (str "Resolving conflicts... please wait. ")))

              (= (type val) replikativ.crdt.cdvcs.stage/Abort) ;; TODO check reapply
              (do
                (sc/transact stage [user #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"] (:aborted val))
                (omdom/div nil (str "Retransacting your changes on new value... " (:aborted val))))

              (isa? (type val) js/Error)
              (omdom/div {:style "color:red"} (pr-str val))

              :else
              (if selected-topiq
                (topiq-arguments val owner val-atom)
                (topiqs val owner)))))))



;; necessary only for initial read below
(read/register-tag-parser! 'replikativ.crdt.CDVCS map->CDVCS)

(go
  (def store
    ;; initialize the store in memory here for development processes
    ;; we need to defer record instantiation until cljs load time,
    ;; otherwise datascript and CDVCS are undefined for the clj
    ;; analyzer

    ;; NOTE: this automagically works with the server as well, as the
    ;; store is synchronized on connection
    (<! (new-mem-store
         (atom (read-string "{#uuid \"3b0197ff-84da-57ca-adb8-94d2428c6227\" (fn store-blob-trans [old params] (if *custom-store-fn* (*custom-store-fn* old params) old)), #uuid \"3004b2bd-3dd9-5524-a09c-2da166ffad6a\" {:transactions [], :parents [], :crdt :cdvcs, :version 1, :ts #inst \"2016-02-25T22:02:59.868-00:00\", :author \"eve@topiq.es\", :crdt-refs #{}}, [\"eve@topiq.es\" #uuid \"26558dfe-59bb-4de4-95c3-4028c56eb5b5\"] {:crdt :cdvcs, :description nil, :public false, :state #replikativ.crdt.CDVCS{:commit-graph {#uuid \"156c2e83-3159-588f-a075-245e01a447a0\" [#uuid \"3004b2bd-3dd9-5524-a09c-2da166ffad6a\"], #uuid \"3004b2bd-3dd9-5524-a09c-2da166ffad6a\" []}, :heads #{#uuid \"156c2e83-3159-588f-a075-245e01a447a0\"}, :version 1}}, #uuid \"0a560c8a-a1b6-5823-9955-8a6f111e4051\" (fn [_ new] new), #uuid \"334e2480-c2dc-5c26-8208-37274e1e7aca\" #datascript/DB {:schema {:up-votes {:db/cardinality :db.cardinality/many}, :down-votes {:db/cardinality :db.cardinality/many}, :posts {:db/cardinality :db.cardinality/many}, :arguments {:db/cardinality :db.cardinality/many}, :hashtags {:db/cardinality :db.cardinality/many}}, :datoms []}, #uuid \"156c2e83-3159-588f-a075-245e01a447a0\" {:transactions [[#uuid \"0a560c8a-a1b6-5823-9955-8a6f111e4051\" #uuid \"334e2480-c2dc-5c26-8208-37274e1e7aca\"]], :ts #inst \"2016-02-25T22:03:03.762-00:00\", :parents [#uuid \"3004b2bd-3dd9-5524-a09c-2da166ffad6a\"], :crdt :cdvcs, :version 1, :author \"eve@topiq.es\", :crdt-refs #{}}}")))))

  ;; ensure we are only tracking necessary CRDTs
  (<! (k/assoc-in store [:peer-config :sub-filter]
                  {"eve@topiq.es" #{#uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"}}))

  (def hooks (atom {}))


  (def peer (<! (client-peer store err-ch
                             :middleware (comp (partial block-detector :client-core)
                                               (partial hook hooks store)
                                               (partial fetch store (atom {}) err-ch)
                                               ensure-hash
                                               (partial block-detector :client-surface)
                                               filtered-subscriptions))))

  (def stage (<! (s/create-stage! "eve@topiq.es" peer err-ch)))


  (<! (s/subscribe-crdts! stage {"eve@topiq.es" #{#uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"}}))


  ;; comment this out if you want to develop locally, e.g. with figwheel
  (<! (s/connect!
       stage
       (str
        (if ssl?  "wss://" "ws://")
        (.getDomain uri)
        (when (= (.getDomain uri) "localhost")
          (str ":" 8080 #_(.getPort uri)))
        "/replikativ/ws")))


  (defn login-fn [new-user]
    (go
      (swap! stage assoc-in [:config :user] new-user)
      (<! (sc/fork! stage ["eve@topiq.es" #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"]))
      (swap! hooks assoc ["eve@topiq.es" #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"]
             [[new-user #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"]
              default-integrity-fn true])))

  (om/root
   (partial navbar-view login-fn)
   nil
   {:target (. js/document (getElementById "collapsed-navbar-group"))})


  ;; stream changes into datascript
  (let [[p _] (get-in @stage [:volatile :chans])
        pub-ch (chan)]
    (async/sub p :pub/downstream pub-ch)
    (go-loop [{{{new-heads :heads} :op
                method :method}
               :downstream :as pub
               :keys [user crdt-id]} (<! pub-ch)
              applied #{}]
      (let [suser (get-in @stage [:config :user])
            cdvcs (or (get-in @stage [suser #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5" :state])
                      (key->crdt :cdvcs))
            {:keys [heads commit-graph]} (-downstream cdvcs pub)]
        (cond (not (and (= user suser)
                        (= crdt-id  #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5")))
              (recur (<! pub-ch) applied)

              (= 1 (count heads))
              (let [txs (mapcat :transactions (<! (commit-history-values store commit-graph
                                                                         (first new-heads)
                                                                         :to-ignore (set applied))))]
                (swap! val-atom
                       #(reduce (partial trans-apply eval-fn)
                                (or (:lca-value %) ;; conflict, buggy in this case still
                                    ;; misses commits between lca and merge
                                    %)
                                (filter (comp not empty?) txs)))
                (recur (<! pub-ch) (set/union applied (keys commit-graph))))

              :else
              (do
                (reset! val-atom (<! (summarize-conflict store eval-fn cdvcs)))
                (recur (<! pub-ch) applied))))))


  (om/root
   (partial topiqs-view stage)
   val-atom
   {:target (. js/document (getElementById "topiq-container"))}))


;; prototype helpers to reimport DB on schema changes etc.
;; TODO serialize datascript/DB properly with incognito
(defn ^:export print-db []
  (println (pr-str @val-atom)))

(defn ^:export read-db [db-str]
  (go
    (.info js/console (<! (sc/transact stage ["eve@topiq.es" #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"]
                                       '(fn [old params] (d/db-with old params))
                                       (mapv (fn [datom] (into [:db/add] datom))
                                             (read-string db-str)))))
    (.info js/console (<! (sc/commit! stage {"eve@topiq.es" #{#uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"}})))))



(comment
  ;; jack in figwheel cljs REPL
  (require 'figwheel-sidecar.repl-api)
  (figwheel-sidecar.repl-api/cljs-repl)

  (get-in @(:state store) [["eve@topiq.es" #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"] :state])
  (dissoc (get-in @stage ["eve@topiq.es" #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5" :state]) :store)

  (dissoc (get-in @stage ["foo@bar.com" #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5" :state]) :store)

  (get-in @stage ["banana@joe.com" #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5" :state :heads])

  (get-in @stage [:config])



  ;; recreate database
  (sc/create-cdvcs! stage
                    :id #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"
                    :description "topiq discourse.")
  (let [schema {:up-votes {:db/cardinality :db.cardinality/many}
                :down-votes {:db/cardinality :db.cardinality/many}
                :posts {:db/cardinality :db.cardinality/many}
                :arguments {:db/cardinality :db.cardinality/many}
                :hashtags {:db/cardinality :db.cardinality/many}}
        conn   (d/create-conn schema)]
    (go
      (<! (sc/transact stage ["eve@topiq.es" #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"]
                       '(fn [_ new] new)
                       @conn))
      (<! (sc/commit! stage {"eve@topiq.es" #{#uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"}}))))


  (go
    (let [start (js/Date.)]
      (doseq [i (range 1e1)]
        (<! (sc/transact stage ["eve@topiq.es" #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"]
                         '(fn [old params] (d/db-with old params))
                         [{:db/unique-identity [:item/id (uuid)]
                           :title (str i)
                           :detail-text  (str i)
                           :author "benchmark@topiq.es"
                           :ts (js/Date.)}]))
        (<! (sc/commit! stage {"eve@topiq.es" #{#uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"}}))
        (<! (timeout 100)))
      (def benchmark (- (js/Date.) start))))

  (-> @stage :volatile :peer deref :volatile :store :state deref keys))
