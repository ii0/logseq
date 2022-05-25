(ns frontend.components.repo
  (:require [clojure.string :as string]
            [frontend.components.widgets :as widgets]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.db :as db]
            [frontend.handler.page :as page-handler]
            [frontend.handler.repo :as repo-handler]
            [frontend.handler.web.nfs :as nfs-handler]
            [frontend.modules.shortcut.core :as shortcut]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [reitit.frontend.easy :as rfe]
            [rum.core :as rum]
            [frontend.mobile.util :as mobile-util]
            [logseq.graph-parser.text :as text]
            [promesa.core :as p]
            [electron.ipc :as ipc]
            [goog.object :as gobj]
            [frontend.components.encryption :as encryption]
            [frontend.encrypt :as e]
            [cljs.core.async :as async :refer [go <!]]
            [frontend.handler.file-sync :as file-sync]))

(rum/defc add-repo
  [args]
  (if-let [graph-types (get-in args [:query-params :graph-types])]
    (let [graph-types-s (->> (string/split graph-types #",")
                             (mapv keyword))]
      (when (seq graph-types-s)
        (widgets/add-graph :graph-types graph-types-s)))
    (widgets/add-graph)))

(rum/defc normalized-graph-label
  [{:keys [url remote? GraphName GraphUUID] :as graph} on-click]
  (when graph
    (let [local? (config/local-db? url)]
      [:span.flex.items-center
       (if local?
         (let [local-dir (config/get-local-dir url)
               graph-name (text/get-graph-name-from-path local-dir)]
           [:a {:title    local-dir
                :on-click #(on-click graph)}
            [:span graph-name (and GraphName [:strong.px-1 "(" GraphName ")"])]
            (when remote? [:strong.pr-1 (ui/icon "cloud")])])

         [:a {:title  GraphUUID
              :on-click #(on-click graph)}
          (db/get-repo-path (or url GraphName))
          (when remote? [:strong.pl-1 (ui/icon "cloud")])])])))

(rum/defc repos < rum/reactive
  []
  (let [login? (boolean (state/sub :auth/id-token))
        repos (state/sub [:me :repos])
        repos (util/distinct-by :url repos)
        remotes (state/sub [:file-sync/remote-graphs :graphs])
        repos (if (and login? (seq remotes))
                (repo-handler/combine-local-&-remote-graphs repos remotes) repos)
        repos (remove #(= (:url %) config/local-repo) repos)]
    (if (seq repos)
      [:div#graphs
       [:h1.title "All Graphs"]
       [:p.ml-2.opacity-70
        "A \"graph\" in Logseq means a local directory."]

       [:div.pl-1.content.mt-3
        [:div.flex.flex-row.my-4
         (when (or (nfs-handler/supported?)
                   (mobile-util/native-platform?))
           [:div.mr-8
            (ui/button
             (t :open-a-directory)
             :on-click #(page-handler/ls-dir-files! shortcut/refresh!))])]
        (for [{:keys [url remote? GraphUUID GraphName] :as repo} repos
              :let [only-cloud? (and remote? (nil? url))]]
          [:div.flex.justify-between.mb-4 {:key (or url GraphUUID)}
           (normalized-graph-label repo #(if only-cloud?
                                           (state/pub-event! [:graph/pick-dest-to-sync repo])
                                           (state/pub-event! [:graph/switch url])))

           [:div.controls
            (when (or (e/encrypted-db? url) (and url remote?))
              [:a.control {:title    "Show encryption information about this graph"
                           :on-click (fn []
                                       (if remote?
                                         (state/pub-event! [:modal/remote-encryption-input-pw-dialog url repo])
                                         (state/set-modal! (encryption/encryption-dialog url))))}
               "🔐"])

            [:a.text-gray-400.ml-4.font-medium.text-sm
             {:title    (if only-cloud?
                          "Warning: It can't be recovered!"
                          "No worries, unlink this graph will clear its cache only, it does not remove your files on the disk.")
              :on-click (fn []
                          (if only-cloud?
                            (when (js/confirm (str "Are you sure remove remote this graph (" GraphName ")!"))
                              (go (<! (file-sync/delete-graph GraphUUID))
                                  (file-sync/load-session-graphs)))
                            (do
                              (repo-handler/remove-repo! repo)
                              (file-sync/load-session-graphs))))}
             (if only-cloud?
               [:span.text-red-600 "Remove"]
               "Unlink")]]])]]
      (widgets/add-graph))))

(defn refresh-cb []
  (page-handler/create-today-journal!)
  (shortcut/refresh!))

(defn- check-multiple-windows?
  [state]
  (when (util/electron?)
    (p/let [multiple-windows? (ipc/ipc "graphHasMultipleWindows" (state/get-current-repo))]
      (reset! (::electron-multiple-windows? state) multiple-windows?))))

(defn- repos-dropdown-links [repos current-repo *multiple-windows?]
  (let [switch-repos (if-not (nil? current-repo)
                       (remove (fn [repo] (= current-repo (:url repo))) repos) repos) ; exclude current repo
        repo-links (mapv
                    (fn [{:keys [url remote? GraphName GraphUUID] :as graph}]
                      (let [local? (config/local-db? url)
                            repo-path (if local? (db/get-repo-name url) GraphName )
                            short-repo-name (if local? (text/get-graph-name-from-path repo-path) GraphName)]
                        {:title        [:span.flex.items-center short-repo-name
                                        (when remote? [:span.pl-1
                                                       {:title (str "<" GraphName "> #" GraphUUID)}
                                                       (ui/icon "cloud")])]
                         :hover-detail repo-path ;; show full path on hover
                         :options      {:class    "ml-1"
                                        :on-click (fn [e]
                                                    (if (gobj/get e "shiftKey")
                                                      (state/pub-event! [:graph/open-new-window url])
                                                      (if-not local?
                                                        (state/pub-event! [:graph/pick-dest-to-sync graph])
                                                        (state/pub-event! [:graph/switch url]))))}}))
                    switch-repos)
        refresh-link (let [nfs-repo? (config/local-db? current-repo)]
                       (when (and nfs-repo?
                                  (not= current-repo config/local-repo)
                                  (or (nfs-handler/supported?)
                                      (mobile-util/native-platform?)))
                         {:title (t :sync-from-local-files)
                          :hover-detail (t :sync-from-local-files-detail)
                          :options {:on-click
                                    (fn []
                                      (state/pub-event!
                                       [:modal/show
                                        [:div {:style {:max-width 700}}
                                         [:p (t :sync-from-local-changes-detected)]
                                         (ui/button
                                          (t :yes)
                                          :autoFocus "on"
                                          :large? true
                                          :on-click (fn []
                                                      (state/close-modal!)
                                                      (nfs-handler/refresh! (state/get-current-repo) refresh-cb)))]]))}}))
        reindex-link {:title        (t :re-index)
                      :hover-detail (t :re-index-detail)
                      :options (cond->
                                {:on-click
                                 (fn []
                                   (state/pub-event! [:graph/ask-for-re-index *multiple-windows?]))})}
        new-window-link (when (util/electron?)
                          {:title        (t :open-new-window)
                           :options {:on-click #(state/pub-event! [:graph/open-new-window nil])}})]
    (->>
     (concat repo-links
             [(when (seq repo-links) {:hr true})
              {:title (t :new-graph) :options {:on-click #(page-handler/ls-dir-files! shortcut/refresh!)}}
              {:title (t :all-graphs) :options {:href (rfe/href :repos)}}
              refresh-link
              reindex-link
              new-window-link])
     (remove nil?))))

(rum/defcs repos-dropdown < rum/reactive
  (rum/local false ::electron-multiple-windows?)
  [state]
  (let [multiple-windows? (::electron-multiple-windows? state)
        current-repo (state/sub :git/current-repo)
        login? (boolean (state/sub :auth/id-token))]
    (when (or login? current-repo)
      (let [repos (state/sub [:me :repos])
            remotes (state/sub [:file-sync/remote-graphs :graphs])
            repos (if (and (seq remotes) login?)
                    (repo-handler/combine-local-&-remote-graphs repos remotes) repos)
            links (repos-dropdown-links repos current-repo multiple-windows?)
            render-content (fn [{:keys [toggle-fn]}]
                             (let [valid-remotes-but-locals? (and (seq repos) (not (some :url repos)))
                                   remote? (when-not valid-remotes-but-locals?
                                             (:remote? (first (filter #(= current-repo (:url %)) repos))))
                                   repo-path (if-not valid-remotes-but-locals?
                                               (db/get-repo-name current-repo) "")
                                   short-repo-name (if-not valid-remotes-but-locals?
                                                     (db/get-short-repo-name repo-path) "Select a Graph")]
                               [:a.item.group.flex.items-center.px-2.py-2.text-sm.font-medium.rounded-md
                                {:on-click (fn []
                                             (check-multiple-windows? state)
                                             (toggle-fn))
                                 :title    repo-path}       ;; show full path on hover
                                (ui/icon "database mr-3" {:style {:font-size 20} :id "database-icon"})
                                [:div.graphs
                                 [:span#repo-switch.block.pr-2.whitespace-nowrap
                                  [:span [:span#repo-name.font-medium
                                          (if (= config/local-repo short-repo-name) "Demo" short-repo-name)
                                          (when remote? [:span.pl-1 (ui/icon "cloud")])]]
                                  [:span.dropdown-caret.ml-2 {:style {:border-top-color "#6b7280"}}]]]]))
            links-header (cond->
                           {:modal-class (util/hiccup->class
                                           "origin-top-right.absolute.left-0.mt-2.rounded-md.shadow-lg")}
                           (> (count repos) 1)              ; show switch to if there are multiple repos
                           (assoc :links-header [:div.font-medium.text-sm.opacity-60.px-4.pt-2
                                                 "Switch to:"]))]
        (when (seq repos)
          (ui/dropdown-with-links render-content links links-header))))))
