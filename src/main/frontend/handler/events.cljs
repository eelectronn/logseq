(ns frontend.handler.events
  (:refer-clojure :exclude [run!])
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [frontend.context.i18n :refer [t]]
            [frontend.components.diff :as diff]
            [frontend.handler.plugin :as plugin-handler]
            [frontend.fs.capacitor-fs :as capacitor-fs]
            [frontend.components.plugins :as plugin]
            [frontend.components.git :as git-component]
            [frontend.components.shell :as shell]
            [frontend.components.search :as search]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.db-schema :as db-schema]
            [frontend.extensions.srs :as srs]
            [frontend.fs.nfs :as nfs]
            [frontend.fs.watcher-handler :as fs-watcher]
            [frontend.handler.common :as common-handler]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.page :as page-handler]
            [frontend.handler.search :as search-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.handler.repo :as repo-handler]
            [frontend.handler.file :as file-handler]
            [frontend.handler.route :as route-handler]
            [frontend.handler.web.nfs :as nfs-handler]
            [frontend.modules.shortcut.core :as st]
            [frontend.modules.outliner.file :as outliner-file]
            [frontend.commands :as commands]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [rum.core :as rum]
            [frontend.modules.instrumentation.posthog :as posthog]
            [frontend.mobile.util :as mobile-util]
            [promesa.core :as p]
            [frontend.fs :as fs]
            [clojure.string :as string]
            [frontend.util.persist-var :as persist-var]
            [frontend.fs.sync :as sync]
            [frontend.handler.file-sync :as file-sync-handler]
            [frontend.components.file-sync :as file-sync]
            [frontend.components.encryption :as encryption]
            [frontend.encrypt :as encrypt]))

;; TODO: should we move all events here?

(defmulti handle first)

(defn- file-sync-restart! []
  (p/do! (persist-var/load-vars)
         (sync/sync-stop)
         (sync/sync-start)))

(defmethod handle :user/login [[_]]
  (async/go
    (async/<! (file-sync-handler/load-session-graphs))
    (p/let [repos (repo-handler/refresh-repos!)]
      (when-let [repo (state/get-current-repo)]
        (when (some #(and (= (:url %) repo)
                          (vector? (:sync-meta %))
                          (util/uuid-string? (first (:sync-meta %)))
                          (util/uuid-string? (second (:sync-meta %)))) repos)
          (file-sync-restart!))))))

(defmethod handle :user/logout [[_]]
  (file-sync-handler/reset-session-graphs))

(defmethod handle :graph/added [[_ repo {:keys [empty-graph?]}]]
  (db/set-key-value repo :ast/version db-schema/ast-version)
  (search-handler/rebuild-indices!)
  (db/persist! repo)
  (when (state/setups-picker?)
    (if empty-graph?
      (route-handler/redirect! {:to :import :query-params {:from "picker"}})
      (route-handler/redirect-to-home!)))
  (repo-handler/refresh-repos!))

(defn- graph-switch [graph]
  (state/set-current-repo! graph)
  ;; load config
  (common-handler/reset-config! graph nil)
  (st/refresh!)
  (when-not (= :draw (state/get-current-route))
    (route-handler/redirect-to-home!))
  (when-let [dir-name (config/get-repo-dir graph)]
    (fs/watch-dir! dir-name))
  (srs/update-cards-due-count!)
  (state/pub-event! [:graph/ready graph])

  (file-sync-restart!))

(def persist-db-noti-m
  {:before     #(notification/show!
                 (ui/loading (t :graph/persist))
                 :warning)
   :on-error   #(notification/show!
                 (t :graph/persist-error)
                 :error)})

(defn- graph-switch-on-persisted
  "Logic for keeping db sync when switching graphs
   Only works for electron"
  [graph {:keys [persist?]}]
  (let [current-repo (state/get-current-repo)]
    (p/do!
     (when persist?
       (when (util/electron?)
         (p/do!
          (repo-handler/persist-db! current-repo persist-db-noti-m)
          (repo-handler/broadcast-persist-db! graph))))
     (repo-handler/restore-and-setup-repo! graph)
     (graph-switch graph))))

(defmethod handle :graph/switch [[_ graph opts]]
  (if (outliner-file/writes-finished?)
    (graph-switch-on-persisted graph opts)
    (notification/show!
     "Please wait seconds until all changes are saved for the current graph."
     :warning)))

(defmethod handle :graph/pick-dest-to-sync [[_ graph]]
  (state/set-modal! (file-sync/pick-dest-to-sync-panel graph)))

(defmethod handle :graph/open-new-window [[ev repo]]
  (p/let [current-repo (state/get-current-repo)
          target-repo (or repo current-repo)
          _ (repo-handler/persist-db! current-repo persist-db-noti-m) ;; FIXME: redundant when opening non-current-graph window
          _ (when-not (= current-repo target-repo)
              (repo-handler/broadcast-persist-db! repo))]
    (ui-handler/open-new-window! ev repo)))

(defmethod handle :graph/migrated [[_ _repo]]
  (js/alert "Graph migrated."))

(defmethod handle :graph/save [_]
  (repo-handler/persist-db! (state/get-current-repo)
                            {:before     #(notification/show!
                                           (ui/loading (t :graph/save))
                                           :warning)
                             :on-success #(notification/show!
                                           (ui/loading (t :graph/save-success))
                                           :warning)
                             :on-error   #(notification/show!
                                           (t :graph/save-error)
                                           :error)}))

(defn get-local-repo
  []
  (when-let [repo (state/get-current-repo)]
    (when (config/local-db? repo)
      repo)))

(defn ask-permission
  [repo]
  (when
   (and (not (util/electron?))
        (not (mobile-util/native-platform?)))
    (fn [close-fn]
      [:div
       [:p
        "Grant native filesystem permission for directory: "
        [:b (config/get-local-dir repo)]]
       (ui/button
        "Grant"
        :class "ui__modal-enter"
        :on-click (fn []
                    (nfs/check-directory-permission! repo)
                    (close-fn)))])))

(defmethod handle :modal/nfs-ask-permission []
  (when-let [repo (get-local-repo)]
    (state/set-modal! (ask-permission repo))))

(defonce *query-properties (atom {}))
(rum/defc query-properties-settings-inner < rum/reactive
  {:will-unmount (fn [state]
                   (reset! *query-properties {})
                   state)}
  [block shown-properties all-properties _close-fn]
  (let [query-properties (rum/react *query-properties)]
    [:div.p-4
     [:div.font-bold "Properties settings for this query:"]
     (for [property all-properties]
       (let [property-value (get query-properties property)
             shown? (if (nil? property-value)
                      (contains? shown-properties property)
                      property-value)]
         [:div.flex.flex-row.m-2.justify-between.align-items
          [:div (name property)]
          [:div.mt-1 (ui/toggle shown?
                                (fn []
                                  (let [value (not shown?)]
                                    (swap! *query-properties assoc property value)
                                    (editor-handler/set-block-query-properties!
                                     (:block/uuid block)
                                     all-properties
                                     property
                                     value)))
                                true)]]))]))

(defn query-properties-settings
  [block shown-properties all-properties]
  (fn [close-fn]
    (query-properties-settings-inner block shown-properties all-properties close-fn)))

(defmethod handle :modal/set-query-properties [[_ block all-properties]]
  (let [block-properties (some-> (get-in block [:block/properties :query-properties])
                                 (common-handler/safe-read-string "Parsing query properties failed"))
        shown-properties (if (seq block-properties)
                           (set block-properties)
                           (set all-properties))
        shown-properties (set/intersection (set all-properties) shown-properties)]
    (state/set-modal! (query-properties-settings block shown-properties all-properties))))

(defmethod handle :modal/show-cards [_]
  (state/set-modal! srs/global-cards {:id :srs
                                      :label "flashcards__cp"}))

(defmethod handle :modal/show-instruction [_]
  (state/set-modal! capacitor-fs/instruction {:id :instruction
                                              :label "instruction__cp"}))

(defmethod handle :modal/show-themes-modal [_]
  (plugin/open-select-theme!))

(rum/defc modal-output
  [content]
  content)

(defmethod handle :modal/show [[_ content]]
  (state/set-modal! #(modal-output content)))

(defmethod handle :modal/set-git-username-and-email [[_ _content]]
  (state/set-modal! git-component/set-git-username-and-email))

(defmethod handle :page/title-property-changed [[_ old-title new-title]]
  (page-handler/rename! old-title new-title))

(defmethod handle :page/create [[_ page-name opts]]
  (page-handler/create! page-name opts))

(defmethod handle :page/create-today-journal [[_ _repo]]
  (p/let [_ (page-handler/create-today-journal!)]
    (ui-handler/re-render-root!)))

(defmethod handle :file/not-matched-from-disk [[_ path disk-content db-content]]
  (state/clear-edit!)
  (when-let [repo (state/get-current-repo)]
    (when (and disk-content db-content
               (not= (util/trim-safe disk-content) (util/trim-safe db-content)))
      (state/set-modal! #(diff/local-file repo path disk-content db-content)
                        {:label "diff__cp"}))))

(defmethod handle :modal/display-file-version [[_ path content hash]]
  (p/let [content (when content (encrypt/decrypt content))]
    (state/set-modal! #(git-component/file-specific-version path hash content))))

(defmethod handle :graph/ready [[_ repo]]
  (search-handler/rebuild-indices-when-stale! repo)
  (repo-handler/graph-ready! repo))

(defmethod handle :notification/show [[_ {:keys [content status clear?]}]]
  (notification/show! content status clear?))

(defmethod handle :command/run [_]
  (when (util/electron?)
    (state/set-modal! shell/shell)))

(defmethod handle :go/search [_]
  (state/set-modal! search/search-modal
                    {:fullscreen? false
                     :close-btn?  false}))

(defmethod handle :go/plugins [_]
  (plugin/open-plugins-modal!))

(defmethod handle :go/plugins-waiting-lists [_]
  (plugin/open-waiting-updates-modal!))

(defmethod handle :go/plugins-settings [[_ pid nav? title]]
  (if pid
    (do
      (state/set-state! :plugin/focused-settings pid)
      (state/set-state! :plugin/navs-settings? (not (false? nav?)))
      (plugin/open-focused-settings-modal! title))
    (state/close-sub-modal! "ls-focused-settings-modal")))

(defmethod handle :go/proxy-settings [[_ agent-opts]]
  (state/set-sub-modal!
    (fn [_] (plugin/user-proxy-settings-panel agent-opts))
    {:id :https-proxy-panel :center? true}))


(defmethod handle :redirect-to-home [_]
  (page-handler/create-today-journal!))

(defmethod handle :instrument [[_ {:keys [type payload]}]]
  (posthog/capture type payload))

(defmethod handle :exec-plugin-cmd [[_ {:keys [pid cmd action]}]]
  (commands/exec-plugin-simple-command! pid cmd action))

(defmethod handle :shortcut-handler-refreshed [[_]]
  (when-not @st/*inited?
    (reset! st/*inited? true)
    (st/consume-pending-shortcuts!)))

(defmethod handle :mobile/keyboard-will-show [[_ keyboard-height]]
  (let [main-node (util/app-scroll-container-node)]
    (state/set-state! :mobile/show-tabbar? false)
    (state/set-state! :mobile/show-toolbar? true)
    (when (mobile-util/native-ios?)
      (reset! util/keyboard-height keyboard-height)
      (set! (.. main-node -style -marginBottom) (str keyboard-height "px"))
      (when-let [card-preview-el (js/document.querySelector ".cards-review")]
        (set! (.. card-preview-el -style -marginBottom) (str keyboard-height "px")))
      (js/setTimeout (fn []
                       (let [toolbar (.querySelector main-node "#mobile-editor-toolbar")]
                         (set! (.. toolbar -style -bottom) (str keyboard-height "px"))))
                     100))))

(defmethod handle :mobile/keyboard-will-hide [[_]]
  (let [main-node (util/app-scroll-container-node)]
    (state/set-state! :mobile/show-toolbar? false)
    (state/set-state! :mobile/show-tabbar? true)
    (when (mobile-util/native-ios?)
      (when-let [card-preview-el (js/document.querySelector ".cards-review")]
        (set! (.. card-preview-el -style -marginBottom) "0px"))
      (set! (.. main-node -style -marginBottom) "0px"))))

(defmethod handle :plugin/consume-updates [[_ id pending? updated?]]
  (let [downloading? (:plugin/updates-downloading? @state/state)]

    (when-let [coming (and (not downloading?)
                           (get-in @state/state [:plugin/updates-coming id]))]
      (let [error-code (:error-code coming)
            error-code (if (= error-code (str :no-new-version)) nil error-code)]
        (when (or pending? (not error-code))
          (notification/show!
            (str "[Checked]<" (:title coming) "> " error-code)
            (if error-code :error :success)))))

    (if (and updated? downloading?)
      ;; try to start consume downloading item
      (if-let [n (state/get-next-selected-coming-update)]
        (plugin-handler/check-or-update-marketplace-plugin
         (assoc n :only-check false :error-code nil)
         (fn [^js e] (js/console.error "[Download Err]" n e)))
        (plugin-handler/close-updates-downloading))

      ;; try to start consume pending item
      (if-let [n (second (first (:plugin/updates-pending @state/state)))]
        (plugin-handler/check-or-update-marketplace-plugin
         (assoc n :only-check true :error-code nil)
         (fn [^js e]
           (notification/show! (.toString e) :error)
           (js/console.error "[Check Err]" n e)))
        ;; try to open waiting updates list
        (when (and pending? (seq (state/all-available-coming-updates)))
          (plugin/open-waiting-updates-modal!))))))

(defmethod handle :plugin/hook-db-tx [[_ {:keys [blocks tx-data tx-meta] :as payload}]]
  (when-let [payload (and (seq blocks)
                          (merge payload {:tx-data (map #(into [] %) tx-data)
                                          :tx-meta (dissoc tx-meta :editor-cursor)}))]
    (plugin-handler/hook-plugin-db :changed payload)
    (plugin-handler/hook-plugin-block-changes payload)))

(defmethod handle :backup/broken-config [[_ repo content]]
  (when (and repo content)
    (let [path (config/get-config-path)
          broken-path (string/replace path "/config.edn" "/broken-config.edn")]
      (p/let [_ (fs/write-file! repo (config/get-repo-dir repo) broken-path content {})
              _ (file-handler/alter-file repo path config/config-default-content {:skip-compare? true})]
        (notification/show!
         [:p.content
          "It seems that your config.edn is broken. We've restored it with the default content and saved the previous content to the file logseq/broken-config.edn."]
         :warning
         false)))))

(defmethod handle :file-watcher/changed [[_ ^js event]]
  (let [type (.-event event)
        payload (-> event
                    (js->clj :keywordize-keys true)
                    (update :path js/decodeURI))]
    (fs-watcher/handle-changed! type payload)
    (sync/file-watch-handler type payload)))

(defmethod handle :rebuild-slash-commands-list [[_]]
  (page-handler/rebuild-slash-commands-list!))

(defmethod handle :graph/ask-for-re-index [[_ *multiple-windows?]]
  (if (and (util/atom? *multiple-windows?) @*multiple-windows?)
    (handle
     [:modal/show
      [:div
       [:p (t :re-index-multiple-windows-warning)]]])
    (handle
     [:modal/show
      [:div {:style {:max-width 700}}
       [:p (t :re-index-discard-unsaved-changes-warning)]
       (ui/button
         (t :yes)
         :autoFocus "on"
         :large? true
         :on-click (fn []
                     (state/close-modal!)
                     (repo-handler/re-index!
                      nfs-handler/rebuild-index!
                      page-handler/create-today-journal!)))]])))

;; encryption
(defmethod handle :modal/encryption-setup-dialog [[_ repo-url close-fn]]
  (state/set-modal!
   (encryption/encryption-setup-dialog repo-url close-fn)))

(defmethod handle :modal/encryption-input-secret-dialog [[_ repo-url db-encrypted-secret close-fn]]
  (state/set-modal!
   (encryption/encryption-input-secret-dialog
    repo-url
    db-encrypted-secret
    close-fn)))

(defmethod handle :modal/remote-encryption-input-pw-dialog [[_ repo-url remote-graph-info close-fn]]
  (state/set-modal!
    (encryption/input-password
      repo-url close-fn (assoc remote-graph-info :type :remote :repo repo-url))))

(defmethod handle :journal/insert-template [[_ page-name]]
  (let [page-name (util/page-name-sanity-lc page-name)]
    (when-let [page (db/pull [:block/name page-name])]
      (when (db/page-empty? (state/get-current-repo) page-name)
        (when-let [template (state/get-default-journal-template)]
          (editor-handler/insert-template!
           nil
           template
           {:target page}))))))

(defn run!
  []
  (let [chan (state/get-events-chan)]
    (async/go-loop []
      (let [payload (async/<! chan)]
        (try
          (handle payload)
          (catch js/Error error
            (let [type :handle-system-events/failed]
              (js/console.error (str type) (clj->js payload) "\n" error)
              (state/pub-event! [:instrument {:type    type
                                              :payload payload
                                              :error error}])))))
      (recur))
    chan))
