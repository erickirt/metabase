(ns metabase-enterprise.snippet-collections.models.native-query-snippet.permissions
  "EE implementation of NativeQuerySnippet permissions."
  (:require
   [metabase.models.interface :as mi]
   [metabase.native-query-snippets.core :as snippets]
   [metabase.permissions.core :as perms]
   [metabase.premium-features.core :refer [defenterprise]]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [toucan2.core :as t2]))

(mu/defn- has-parent-collection-perms?
  [snippet       :- [:map [:collection_id [:maybe ms/PositiveInt]]]
   read-or-write :- [:enum :read :write]]
  (mi/current-user-has-full-permissions? (perms/perms-objects-set-for-parent-collection "snippets" snippet read-or-write)))

(defenterprise can-read?
  "Can the current User read this `snippet`?"
  :feature :snippet-collections
  ([snippet]
   (and
    (not (perms/sandboxed-user?))
    (snippets/has-any-native-permissions?)
    (has-parent-collection-perms? snippet :read)))
  ([model id]
   (can-read? (t2/select-one [model :collection_id] :id id))))

(defenterprise can-write?
  "Can the current User edit this `snippet`?"
  :feature :snippet-collections
  ([snippet]
   (and
    (not (perms/sandboxed-user?))
    (snippets/has-any-native-permissions?)
    (has-parent-collection-perms? snippet :write)))
  ([model id]
   (can-write? (t2/select-one [model :collection_id] :id id))))

(defenterprise can-create?
  "Can the current User save a new Snippet with the values in `m`?"
  :feature :snippet-collections
  [_model m]
  (and
   (not (perms/sandboxed-user?))
   (snippets/has-any-native-permissions?)
   (has-parent-collection-perms? m :write)))

(defenterprise can-update?
  "Can the current User apply a map of `changes` to a `snippet`?"
  :feature :snippet-collections
  [snippet changes]
  (and
   (not (perms/sandboxed-user?))
   (snippets/has-any-native-permissions?)
   (has-parent-collection-perms? snippet :write)
   (or (not (contains? changes :collection_id))
       (has-parent-collection-perms? changes :write))))
