(ns metabase.warehouses.models.database
  (:require
   [clojure.core.match :refer [match]]
   [clojure.data :as data]
   [medley.core :as m]
   [metabase.analytics.core :as analytics]
   [metabase.api.common :as api]
   [metabase.app-db.core :as mdb]
   [metabase.audit-app.core :as audit]
   [metabase.driver :as driver]
   [metabase.driver.impl :as driver.impl]
   [metabase.driver.settings :as driver.settings]
   [metabase.driver.util :as driver.u]
   [metabase.lib.schema.id :as lib.schema.id]
   [metabase.models.interface :as mi]
   [metabase.models.serialization :as serdes]
   [metabase.permissions.core :as perms]
   [metabase.premium-features.core :as premium-features :refer [defenterprise]]
   ;; Trying to use metabase.search would cause a circular reference ;_;
   [metabase.search.spec :as search.spec]
   [metabase.secrets.core :as secret]
   [metabase.settings.core :as setting]
   [metabase.sync.schedules :as sync.schedules]
   [metabase.util :as u]
   [metabase.util.honey-sql-2 :as h2x]
   [metabase.util.i18n :refer [trs]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.quick-task :as quick-task]
   [methodical.core :as methodical]
   [toucan2.core :as t2]
   [toucan2.pipeline :as t2.pipeline]
   [toucan2.realize :as t2.realize]
   [toucan2.tools.with-temp :as t2.with-temp]))

(set! *warn-on-reflection* true)

;;; ----------------------------------------------- Entity & Lifecycle -----------------------------------------------

(methodical/defmethod t2/table-name :model/Database [_model] :metabase_database)

(methodical/defmethod t2.pipeline/results-transform [:toucan.result-type/instances :model/Database]
  [query-type model]
  (comp
   (next-method query-type model)
    ;; This is for safety - if a secret ever gets stored in details we don't want it to leak.
    ;; This will also help to secure properties that we set to secret in the future.
   (map secret/clean-secret-properties-from-database)))

(t2/deftransforms :model/Database
  {:details                     mi/transform-encrypted-json
   :engine                      mi/transform-keyword
   :metadata_sync_schedule      mi/transform-cron-string
   :cache_field_values_schedule mi/transform-cron-string
   :start_of_week               mi/transform-keyword
   :settings                    mi/transform-encrypted-json
   :dbms_version                mi/transform-json})

(methodical/defmethod t2/model-for-automagic-hydration [:default :database] [_model _k] :model/Database)
(methodical/defmethod t2/model-for-automagic-hydration [:default :db]       [_model _k] :model/Database)

(doto :model/Database
  (derive :metabase/model)
  (derive :hook/timestamped?)
  ;; Deliberately **not** deriving from `:hook/entity-id` because we should not be randomizing the `entity_id`s on
  ;; databases, tables or fields. Since the sync process can create them in multiple instances, randomizing them would
  ;; cause duplication rather than good matching if the two instances are later linked by serdes.
  #_(derive :hook/entity-id))

(methodical/defmethod t2.with-temp/do-with-temp* :before :model/Database
  [_model _explicit-attributes f]
  (fn [temp-object]
    ;; Grant All Users full perms on the temp-object so that tests don't have to manually set permissions
    (perms/set-database-permission! (perms/all-users-group) temp-object :perms/view-data :unrestricted)
    (perms/set-database-permission! (perms/all-users-group) temp-object :perms/create-queries :query-builder-and-native)
    (perms/set-database-permission! (perms/all-users-group) temp-object :perms/download-results :one-million-rows)
    (f temp-object)))

(defn- should-read-audit-db?
  "Audit Database should only be fetched if audit app is enabled."
  [database-id]
  (and (not (premium-features/enable-audit-app?)) (= database-id audit/audit-db-id)))

(def ^{:arglists '([db-id])
       :private  true} db-id->router-db-id
  (mdb/memoize-for-application-db
   (fn [db-id]
     (t2/select-one-fn :router_database_id :model/Database :id db-id))))

(defmethod mi/can-read? :model/Database
  ([instance]
   (mi/can-read? :model/Database (u/the-id instance)))
  ([_model database-id]
   (cond
     (should-read-audit-db? database-id) false
     (db-id->router-db-id database-id) (mi/can-read? :model/Database (db-id->router-db-id database-id))
     :else (contains? #{:query-builder :query-builder-and-native}
                      (perms/most-permissive-database-permission-for-user
                       api/*current-user-id*
                       :perms/create-queries
                       database-id)))))

(defenterprise current-user-can-write-db?
  "OSS implementation. Returns a boolean whether the current user can write the given field."
  metabase-enterprise.advanced-permissions.common
  [_db-id]
  (mi/superuser?))

(defn- can-write?
  [db-id]
  (or (some-> db-id db-id->router-db-id can-write?)
      (and (not= db-id audit/audit-db-id)
           (current-user-can-write-db? db-id))))

(defmethod mi/can-write? :model/Database
  ;; Lack of permission to change database details will also exclude the `details` field from the HTTP response,
  ;; cf. the implementation of [[metabase.models.interface/to-json]] for `:model/Database`.
  ([{:keys [is_attached_dwh] :as instance}]
   (and (can-write? (u/the-id instance))
        (not is_attached_dwh)))
  ([_model pk]
   (and (can-write? pk)
        (not (:is_attached_dwh (t2/select-one :model/Database :id pk))))))

(mu/defmethod mi/visible-filter-clause :model/Database
  [_model column-or-exp user-info permission-mapping]
  [:in column-or-exp
   (perms/visible-database-filter-select user-info permission-mapping)])

(defn- infer-db-schedules
  "Infer database schedule settings based on its options."
  [{:keys [details is_full_sync is_on_demand cache_field_values_schedule metadata_sync_schedule] :as database}]
  (match [(boolean (:let-user-control-scheduling details)) is_full_sync is_on_demand]
    [false _ _]
    (merge
     database
     (sync.schedules/schedule-map->cron-strings
      (sync.schedules/default-randomized-schedule)))

    ;; "Regularly on a schedule"
    ;; -> sync both steps, schedule should be provided
    [true true false]
    (do
      (assert (every? some? [cache_field_values_schedule metadata_sync_schedule]))
      database)

    ;; "Only when adding a new filter" or "Never, I'll do it myself"
    ;; -> Sync metadata only
    [true false _]
    ;; schedules should only contains metadata_sync, but FE might sending both
    ;; so we just manually nullify it here
    (assoc database :cache_field_values_schedule nil)))

(defn- is-destination?
  "Is this database a destination database for some router database?"
  [db]
  (boolean (:router_database_id db)))

(defn should-sync?
  "Should this database be synced?"
  [db]
  (not (is-destination? db)))

(defn- check-and-schedule-tasks-for-db!
  "(Re)schedule sync operation tasks for `database`. (Existing scheduled tasks will be deleted first.)"
  [database]
  (try
    ;; this is done this way to avoid circular dependencies
    (when (should-sync? database)
      ((requiring-resolve 'metabase.sync.task.sync-databases/check-and-schedule-tasks-for-db!) database))
    (catch Throwable e
      (log/error e "Error scheduling tasks for DB"))))

(defn maybe-test-and-migrate-details!
  "When a driver has db-details to test and migrate:
   we loop through them until we find one that works and update the database with the working details."
  [{:keys [engine details] :as database}]
  (if-let [details-to-test (seq (driver/db-details-to-test-and-migrate (keyword engine) details))]
    (do
      (log/infof "Attempting to connect to %d possible legacy details" (count details-to-test))
      (loop [[test-details & tail] details-to-test]
        (if test-details
          (if (driver.u/can-connect-with-details? engine (assoc test-details :engine engine))
            (let [keys-remaining (-> test-details keys set)
                  [_ removed _] (data/diff keys-remaining (-> details keys set))]
              (log/infof "Successfully connected, migrating to: %s" (pr-str {:keys keys-remaining :keys-removed removed}))
              (t2/update! :model/Database (:id database) {:details test-details})
              test-details)
            (recur tail))
          ;; if we go through the list and we can't fine a working detail to test, keep original value
          details)))
    details))

(defn health-check-database!
  "Checks database health off-thread.
   - checks connectivity
   - cleans-up ambiguous legacy, db-details"
  [{:keys [engine] :as database}]
  (when-not (or (:is_audit database) (:is_sample database))
    (log/info (u/format-color :cyan "Health check: queueing %s {:id %d}" (:name database) (:id database)))
    (quick-task/submit-task!
     (fn []
       (let [details (maybe-test-and-migrate-details! database)
             engine (name engine)
             driver (keyword engine)
             details-map (assoc details :engine engine)]
         (try
           (log/info (u/format-color :cyan "Health check: checking %s {:id %d}" (:name database) (:id database)))
           (u/with-timeout (driver.settings/db-connection-timeout-ms)
             (or (driver/can-connect? driver details-map)
                 (throw (Exception. "Failed to connect to Database"))))
           (log/info (u/format-color :green "Health check: success %s {:id %d}" (:name database) (:id database)))
           (analytics/inc! :metabase-database/status {:driver engine :healthy true})

           (catch Throwable e
             (let [humanized-message (some->> (.getMessage e)
                                              (driver/humanize-connection-error-message driver))
                   reason (if (keyword? humanized-message) "user-input" "exception")]
               (log/error e (u/format-color :red "Health check: failure with error %s {:id %d :reason %s :message %s}"
                                            (:name database)
                                            (:id database)
                                            reason
                                            humanized-message))
               (analytics/inc! :metabase-database/status {:driver engine :healthy false :reason reason})))))))))

(defn check-health!
  "Health checks databases connected to metabase asynchronously using a thread pool."
  []
  (analytics/clear! :metabase-database/status)
  (doseq [database (t2/select :model/Database)]
    (health-check-database! database)))

;; TODO - something like NSNotificationCenter in Objective-C would be really really useful here so things that want to
;; implement behavior when an object is deleted can do it without having to put code here

(defn- unschedule-tasks!
  "Unschedule any currently pending sync operation tasks for `database`."
  [database]
  (try
    ((requiring-resolve 'metabase.sync.task.sync-databases/unschedule-tasks-for-db!) database)
    (catch Throwable e
      (log/error e "Error unscheduling tasks for DB."))))

;; TODO -- consider whether this should live HERE or inside the `permissions` module.
(defn- set-new-database-permissions!
  [database]
  (when-not (is-destination? database)
    (t2/with-transaction [_conn]
      (let [all-users-group  (perms/all-users-group)
            non-magic-groups (perms/non-magic-groups)
            non-admin-groups (conj non-magic-groups all-users-group)]
        (if (:is_audit database)
          (doseq [group non-admin-groups]
            (perms/set-database-permission! group database :perms/view-data :unrestricted)
            (perms/set-database-permission! group database :perms/create-queries :no)
            (perms/set-database-permission! group database :perms/download-results :one-million-rows)
            (perms/set-database-permission! group database :perms/manage-table-metadata :no)
            (perms/set-database-permission! group database :perms/manage-database :no))
          (doseq [group non-admin-groups]
            (perms/set-new-database-permissions! group database)))))))

(t2/define-after-insert :model/Database
  [database]
  (u/prog1 database
    (set-new-database-permissions! database)
    ;; schedule the Database sync & analyze tasks This will not do anything when coming
    ;; from [[metabase-enterprise.advanced-config.file/initialize!]], since the scheduler will not be up yet. Thus, we
    ;; call [[metabase.sync.task.sync-databases/check-and-schedule-tasks!]] from [[metabase.core.core/init!]] to
    ;; self-heal.
    (check-and-schedule-tasks-for-db! (t2.realize/realize database))))

(def ^:private ^:dynamic *normalizing-details*
  "Track whether we're calling [[driver/normalize-db-details]] already to prevent infinite
  recursion. [[driver/normalize-db-details]] is actually done for side effects!"
  false)

(t2/define-after-select :model/Database
  [{driver :engine, :as database}]
  (letfn [(normalize-details [db]
            (binding [*normalizing-details* true]
              (driver/normalize-db-details
               driver
               (m/update-existing-in db [:details :auth-provider] keyword))))]
    (cond-> database
      ;; TODO - this is only really needed for API responses. This should be a `hydrate` thing instead!
      (driver.impl/registered? driver)
      (assoc :features (driver.u/features driver (t2.realize/realize database)))

      (and (driver.impl/registered? driver)
           (map? (:details database))
           (not *normalizing-details*))
      normalize-details)))

(mu/defn- delete-database-fields!
  "We need to use toucan to delete the fields instead of cascading deletes because MySQL doesn't support columns with
  cascade delete foreign key constraints in generated columns. #44866

  Use a join to do this so we don't end up with a mega query with > 64k parameters (#58491)

  TODO -- this is an absolutely horrible way to deal with deleting Fields belonging to a Database, there can be
  literally hundreds of thousands of fields and we do an individual follow-on DELETE in :model/Field before-delete for
  each one. I really think we should have kept the FK as an ON DELETE CASCADE. -- Cam"
  [database-id :- ::lib.schema.id/database]
  {:pre [(pos-int? database-id)]}
  (t2/delete! :model/Field (case (mdb/db-type)
                             (:postgres :h2)
                             {:where  [:in :id {:select    [[:field.id :id]]
                                                :from      [[(t2/table-name :model/Field) :field]]
                                                :left-join [[(t2/table-name :model/Table) :table]
                                                            [:= :field.table_id :table.id]]
                                                :where     [:= :table.db_id [:inline database-id]]}]}

                             :mysql
                             {:delete    [:field]
                              :from      [[(t2/table-name :model/Field) :field]]
                              :left-join [[(t2/table-name :model/Table) :table]
                                          [:= :field.table_id :table.id]]
                              :where     [:= :table.db_id [:inline database-id]]})))

(t2/define-before-delete :model/Database
  [{id :id, driver :engine, :as database}]
  (unschedule-tasks! database)
  (secret/delete-orphaned-secrets! database)
  (delete-database-fields! id)
  (try
    (driver/notify-database-updated driver database)
    (catch Throwable e
      (log/error e "Error sending database deletion notification"))))

(defn- maybe-disable-uploads-for-all-dbs!
  "This function maintains the invariant that only one database can have uploads_enabled=true."
  [db]
  (when (:uploads_enabled db)
    (t2/update! :model/Database :uploads_enabled true {:uploads_enabled false :uploads_table_prefix nil :uploads_schema_name nil}))
  db)

(t2/define-before-update :model/Database
  [database]
  (let [changes                       (t2/changes database)
        {new-engine        :engine
         new-settings      :settings} changes
        {is-sample?        :is_sample
         existing-settings :settings
         existing-engine   :engine}   (t2/original database)
        new-engine                    (some-> new-engine keyword)]
    (if (and is-sample?
             new-engine
             (not= new-engine existing-engine))
      (throw (ex-info (trs "The engine on a sample database cannot be changed.")
                      {:status-code     400
                       :existing-engine existing-engine
                       :new-engine      new-engine}))
      (u/prog1 (cond-> database
                 ;; If the engine doesn't support nested field columns, `json_unfolding` must be nil
                 (and (some? (:details changes))
                      (not (driver.u/supports? (or new-engine existing-engine) :nested-field-columns database)))
                 (update :details dissoc :json_unfolding)

                 (or
                  ;if there is any changes in user control setting
                  (some? (get-in changes [:details :let-user-control-scheduling]))
                  ;; if the let user control scheduling is already on, we should always try to re-infer it
                  (get-in database [:details :let-user-control-scheduling])
                  ;; if there is a changes in schedules, make sure it respects the settings
                  (some some? [(:cache_field_values_schedule changes) (:metadata_sync_schedule changes)]))
                 infer-db-schedules

                 (some? (:details changes))
                 secret/handle-incoming-client-secrets!

                 (:uploads_enabled changes)
                 maybe-disable-uploads-for-all-dbs!)
        ;; This maintains a constraint that if a driver doesn't support actions, it can never be enabled
        ;; If we drop support for actions for a driver, we'd need to add a migration to disable actions for all databases
        (when (and (:database-enable-actions (or new-settings existing-settings))
                   (not (driver.u/supports? (or new-engine existing-engine) :actions database)))
          (throw (ex-info (trs "The database does not support actions.")
                          {:status-code     400
                           :existing-engine existing-engine
                           :new-engine      new-engine})))
        ;; This maintains a constraint that if a driver doesn't support data editing, it can never be enabled
        ;; If we drop support for a driver, we'd need to add a migration to disable it for all databases
        (when (and (:database-enable-table-editing (or new-settings existing-settings))
                   (not (driver.u/supports? (or new-engine existing-engine) :actions/data-editing database)))
          (throw (ex-info (trs "The database does not support table editing.")
                          {:status-code     400
                           :existing-engine existing-engine
                           :new-engine      new-engine})))))))

(t2/define-after-update :model/Database
  [database]
  ;; This will not do anything when coming from [[metabase-enterprise.advanced-config.file/initialize!]], since the
  ;; scheduler will not be up yet. Thus, we call [[metabase.sync.task.sync-databases/check-and-schedule-tasks!]]
  ;; from [[metabase.core/init!]] to self-heal.
  (check-and-schedule-tasks-for-db! (t2.realize/realize database)))

(t2/define-before-insert :model/Database
  [{:keys [details initial_sync_status], :as database}]
  (-> (merge {:is_full_sync true
              :is_on_demand false}
             database)
      (cond->
       (not details)             (assoc :details {})
       (not initial_sync_status) (assoc :initial_sync_status "incomplete"))
      secret/handle-incoming-client-secrets!
      maybe-disable-uploads-for-all-dbs!
      infer-db-schedules))

(defmethod serdes/hash-fields :model/Database
  [_database]
  [:name :engine])

(defmethod mi/exclude-internal-content-hsql :model/Database
  [_model & {:keys [table-alias]}]
  (let [maybe-alias #(h2x/identifier :field table-alias %)]
    [:not [:or (maybe-alias :is_sample) (maybe-alias :is_audit)]]))

;;; ---------------------------------------------- Hydration / Util Fns ----------------------------------------------

;; only used in tests
(defn tables
  "Return the `Tables` associated with this `Database`."
  [{:keys [id]}]
  ;; TODO - do we want to include tables that should be `:hidden`?
  (t2/select :model/Table :db_id id :active true {:order-by [[:%lower.display_name :asc]]}))

(methodical/defmethod t2/batched-hydrate [:model/Database :tables]
  "Batch hydrate `Tables` for the given `Database`."
  [_model k databases]
  (mi/instances-with-hydrated-data
   databases k
   #(group-by :db_id
              ;; TODO - do we want to include tables that should be `:hidden`?
              (t2/select :model/Table
                         :db_id  [:in (map :id databases)]
                         :active true
                         {:order-by [[:db_id :asc] [:%lower.display_name :asc]]}))
   :id
   {:default []}))

(defn pk-fields
  "Return all the primary key `Fields` associated with this `database`."
  [{:keys [id]}]
  (let [table-ids (t2/select-pks-set 'Table, :db_id id, :active true)]
    (when (seq table-ids)
      (t2/select 'Field, :table_id [:in table-ids], :semantic_type (mdb/isa :type/PK)))))

;;; -------------------------------------------------- JSON Encoder --------------------------------------------------

(defn sensitive-fields-for-db
  "Gets all sensitive fields that should be redacted in API responses for a given database. Delegates to
  driver.u/sensitive-fields using the given database's driver (if valid), so refer to that for full details. If a valid
  driver can't be clearly determined, this simply returns the default set (driver.u/default-sensitive-fields)."
  [database]
  (if (and (some? database) (not-empty database))
    (let [driver (driver.u/database->driver database)]
      (if (some? driver)
        (driver.u/sensitive-fields (driver.u/database->driver database))
        driver.u/default-sensitive-fields))
    driver.u/default-sensitive-fields))

(methodical/defmethod mi/to-json :model/Database
  "When encoding a Database as JSON remove the `details` for any User without write perms for the DB.
  Users with write perms can see the `details` but remove anything resembling a password. No one gets to see this in
  an API response!

  Also remove settings that the User doesn't have read perms for."
  [db json-generator]
  (next-method
   (let [db (if (not (mi/can-write? db))
              (do (log/debug "Fully redacting database details during json encoding.")
                  (dissoc db :details))
              (do (log/debug "Redacting sensitive fields within database details during json encoding.")
                  (-> db
                      (secret/to-json-hydrate-redacted-secrets)
                      (update :details (fn [details]
                                         (reduce
                                          #(m/update-existing %1 %2 (fn [v] (when v secret/protected-password)))
                                          details
                                          (sensitive-fields-for-db db)))))))]
     (update db :settings
             (fn [settings]
               (when (map? settings)
                 (u/prog1
                   (m/filter-keys
                    (fn [setting-name]
                      (try
                        (setting/can-read-setting? setting-name
                                                   (setting/current-user-readable-visibilities))
                        (catch Throwable e
                         ;; there is an known issue with exception is ignored when render API response (#32822)
                         ;; If you see this error, you probably need to define a setting for `setting-name`.
                         ;; But ideally, we should resovle the above issue, and remove this try/catch
                          (log/errorf e "Error checking the readability of %s setting. The setting will be hidden in API response."
                                      setting-name)
                         ;; let's be conservative and hide it by defaults, if you want to see it,
                         ;; you need to define it :)
                          false)))
                    settings)
                   (when (not= <> settings)
                     (log/debug "Redacting non-user-readable database settings during json encoding.")))))))
   json-generator))

;;; ------------------------------------------------ Serialization ----------------------------------------------------
(defmethod serdes/make-spec "Database"
  [_model-name {:keys [include-database-secrets]}]
  {:copy      [:auto_run_queries :cache_field_values_schedule :caveats :dbms_version
               :description :engine :is_audit :is_attached_dwh :is_full_sync :is_on_demand :is_sample
               :metadata_sync_schedule :name :points_of_interest :refingerprint :settings :timezone :uploads_enabled
               :uploads_schema_name :uploads_table_prefix]
   :skip      [;; deprecated field
               :cache_ttl]
   :transform {:created_at          (serdes/date)
               ;; details should be imported if available regardless of options
               :details             {:export-with-context
                                     (fn [current _ details]
                                       (if (and include-database-secrets
                                                (not (:is_attached_dwh current)))
                                         details
                                         ::serdes/skip))
                                     :import identity}
               :creator_id          (serdes/fk :model/User)
               :router_database_id (serdes/fk :model/Database)
               :initial_sync_status {:export identity :import (constantly "complete")}}})

(defmethod serdes/extract-query "Database"
  [model-name {:keys [where]}]
  (t2/reducible-select (keyword "model" model-name) {:where
                                                     [:and
                                                      (or where true)
                                                      [:= :router_database_id nil]]}))

(defmethod serdes/entity-id "Database"
  [_ {:keys [name]}]
  name)

(defmethod serdes/generate-path "Database"
  [_ {:keys [name]}]
  [{:model "Database" :id name}])

(defmethod serdes/load-find-local "Database"
  [[{:keys [id]}]]
  (t2/select-one :model/Database :name id))

(defmethod serdes/storage-path "Database" [{:keys [name]} _]
  ;; ["databases" "db_name" "db_name"] directory for the database with same-named file inside.
  ["databases" name name])

(def ^{:arglists '([table-id])} table-id->database-id
  "Retrieve the `Database` ID for the given table-id."
  (mdb/memoize-for-application-db
   (fn [table-id]
     {:pre [(integer? table-id)]}
     (t2/select-one-fn :db_id :model/Table, :id table-id))))

;;;; ------------------------------------------------- Search ----------------------------------------------------------

(search.spec/define-spec "database"
  {:model        :model/Database
   :attrs        {:archived      false
                  :collection-id false
                  :creator-id    false
                  ;; not sure if this is another bug
                  :database-id   false
                  :created-at    true
                  :updated-at    true}
   :search-terms [:name :description]
   :where        [:= :router_database_id nil]
   :render-terms {:initial-sync-status true}})

(defenterprise hydrate-router-user-attribute
  "OSS implementation. Hydrates router user attribute on the databases."
  metabase-enterprise.database-routing.model
  [_k databases]
  (for [database databases]
    (assoc database :router_user_attribute nil)))

(methodical/defmethod t2/batched-hydrate [:model/Database :router_user_attribute]
  "Batch hydrate `Tables` for the given `Database`."
  [_model k databases]
  (hydrate-router-user-attribute k databases))
