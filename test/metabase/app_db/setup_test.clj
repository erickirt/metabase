(ns metabase.app-db.setup-test
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.test :refer :all]
   [metabase.app-db.connection :as mdb.connection]
   [metabase.app-db.data-source :as mdb.data-source]
   [metabase.app-db.liquibase :as liquibase]
   [metabase.app-db.setup :as mdb.setup]
   [metabase.app-db.test-util :as mdb.test-util]
   [metabase.driver :as driver]
   [metabase.test :as mt]
   [toucan2.core :as t2])
  (:import
   (liquibase.changelog ChangeSet)))

(set! *warn-on-reflection* true)

(deftest verify-db-connection-test
  (testing "Should be able to verify a DB connection"
    (testing "from a jdbc-spec map"
      (#'mdb.setup/verify-db-connection :h2 (mdb.data-source/broken-out-details->DataSource
                                             :h2
                                             {:subprotocol "h2"
                                              :subname     (format "mem:%s" (mt/random-name))
                                              :classname   "org.h2.Driver"})))
    (testing "from a connection URL"
      (#'mdb.setup/verify-db-connection :h2 (mdb.data-source/raw-connection-string->DataSource
                                             (format "jdbc:h2:mem:%s" (mt/random-name)))))))

(deftest setup-db-test
  (testing "Should be able to set up an arbitrary application DB"
    (letfn [(test* [data-source]
              (is (= :done
                     (mdb.setup/setup-db! :h2 data-source true true)))
              (is (= ["Administrators" "All Users"]
                     (mapv :name (jdbc/query {:datasource data-source}
                                             "SELECT name FROM permissions_group ORDER BY name ASC;")))))]
      (let [subname (fn [] (format "mem:%s;DB_CLOSE_DELAY=10" (mt/random-name)))]
        (testing "from a jdbc-spec map"
          (test* (mdb.data-source/broken-out-details->DataSource
                  :h2
                  {:subprotocol "h2"
                   :subname     (subname)
                   :classname   "org.h2.Driver"})))
        (testing "from a connection URL"
          (test* (mdb.data-source/raw-connection-string->DataSource (str "jdbc:h2:" (subname)))))
        (testing "test `create-sample-content?` arg works"
          (doseq [create-sample-content? [true false]]
            (let [data-source (mdb.data-source/raw-connection-string->DataSource (str "jdbc:h2:" (subname)))]
              (mdb.setup/setup-db! :h2 data-source true create-sample-content?)
              (is (= (if create-sample-content?
                       ["E-commerce Insights"]
                       [])
                     (mapv :name (jdbc/query {:datasource data-source}
                                             "SELECT name FROM report_dashboard ORDER BY name ASC;")))))))))))

(deftest setup-fresh-db-test
  (mt/test-drivers #{:h2 :mysql :postgres}
    (testing "can setup a fresh db"
      (mt/with-temp-empty-app-db [conn driver/*driver*]
        (is (= :done
               (mdb.setup/setup-db! driver/*driver* (mdb.connection/data-source) true true)))
        (testing "migrations are executed in the order they are defined"
          (is (= (mdb.test-util/all-liquibase-ids false driver/*driver* conn)
                 (t2/select-pks-vec (liquibase/changelog-table-name conn) {:order-by [[:orderexecuted :asc]]}))))))))

(deftest setup-db-no-auto-migrate-test
  (mt/test-drivers #{:h2 :mysql :postgres}
    (mt/with-temp-empty-app-db [_conn driver/*driver*]
      (testing "Running setup with `auto-migrate?`=false should pass if no migrations exist which need to be run"
        (is (= :done
               (mdb.setup/setup-db! driver/*driver* (mdb.connection/data-source) true false)))

        (is (= :done
               (mdb.setup/setup-db! driver/*driver* (mdb.connection/data-source) false false)))))

    (testing "Setting up DB with `auto-migrate?`=false should exit if any migrations exist which need to be run"
      ;; Use a migration file that intentionally errors with failOnError: false, so that a migration is still unrun
      ;; when we re-run `setup-db!`
      (with-redefs [liquibase/changelog-file "error-migration.yaml"]
        (mt/with-temp-empty-app-db [_conn driver/*driver*]
          (is (= :done
                 (mdb.setup/setup-db! driver/*driver* (mdb.connection/data-source) true false)))

          (is (thrown-with-msg?
               Exception
               #"Database requires manual upgrade."
               (mdb.setup/setup-db! driver/*driver* (mdb.connection/data-source) false false))))))))

(defn- update-to-changelog-id
  [change-log-id conn]
  (liquibase/with-liquibase [liquibase conn]
    (let [unrun-migrations (.listUnrunChangeSets liquibase nil nil)
          run-count        (loop [cnt        1
                                  changesets unrun-migrations]
                             (if (= (.getId ^ChangeSet (first changesets)) change-log-id)
                               cnt
                               (recur (inc cnt) (rest changesets))))]
      (.update liquibase ^Integer run-count nil))))

(deftest setup-a-mb-instance-running-version-lower-than-45
  (mt/test-drivers #{:h2 :mysql :postgres}
    (mt/with-temp-empty-app-db [conn driver/*driver*]
      (with-redefs [liquibase/decide-liquibase-file (fn [& _args] @#'liquibase/changelog-legacy-file)]
        ;; set up a db in a way we have a MB instance running metabase 44
        (update-to-changelog-id "v44.00-000" conn))
      (is (= :done
             (mdb.setup/setup-db! driver/*driver* (mdb.connection/data-source) true false))))))

(deftest setup-a-mb-instance-running-version-greater-than-45
  (mt/test-drivers #{:h2 :mysql :postgres}
    (mt/with-temp-empty-app-db [conn driver/*driver*]
      (with-redefs [liquibase/decide-liquibase-file (fn [& _args] @#'liquibase/changelog-legacy-file)]
             ;; set up a db in a way we have a MB instance running metabase 45
        (update-to-changelog-id "v45.00-001" conn))
      (is (= :done
             (mdb.setup/setup-db! driver/*driver* (mdb.connection/data-source) true false))))))

(deftest downgrade-detection-test
  (mt/test-drivers #{:h2 :mysql :postgres}
    (mt/with-temp-empty-app-db [conn driver/*driver*]
      ;; migrate to v45
      (update-to-changelog-id "v45.00-001" conn)

      ;; the latest changeSet in `000_legacy_migrations.yaml` is `v44.00-044`. We can simulate a downgrade to that
      ;; version by telling Liquibase that's the migrations file.
      (with-redefs [liquibase/decide-liquibase-file (fn [& _args] "migrations/000_legacy_migrations.yaml")]
        (is (thrown-with-msg?
             Exception #"You must run `java --add-opens java.base/java.nio=ALL-UNNAMED -jar metabase.jar migrate down` from version 45."
             (#'mdb.setup/error-if-downgrade-required! (mdb.connection/data-source)))))

      ;; check that the error correctly reports the version to run `downgrade` from
      (update-to-changelog-id "v46.00-001" conn)
      (with-redefs [liquibase/decide-liquibase-file (fn [& _args] "migrations/000_legacy_migrations.yaml")]
        (is (thrown-with-msg?
             Exception #"You must run `java --add-opens java.base/java.nio=ALL-UNNAMED -jar metabase.jar migrate down` from version 46."
             (#'mdb.setup/error-if-downgrade-required! (mdb.connection/data-source))))))))

;; `delete!` below is ok in a parallel test since it's not actually executing anything
#_{:clj-kondo/ignore [:metabase/validate-deftest]}
(deftest ^:parallel build-query-dont-add-delete-from-when-query-contains-delete-test
  (testing "Workaround for https://github.com/camsaul/toucan2/issues/202"
    (is (= {:delete    [:field]
            :from      [[:metabase_field :field]]
            :left-join [[:metabase_table :table] [:= :field.table_id :table.id]]
            :where     [:= :table.db_id [:inline 0]]}
           (t2/build
             (t2/delete! :model/Field
                         {:delete    [:field]
                          :from      [[:metabase_field :field]]
                          :left-join [[:metabase_table :table]
                                      [:= :field.table_id :table.id]]
                          :where     [:= :table.db_id [:inline 0]]}))))))

;; `delete!` below is ok in a parallel test since it's not actually executing anything
#_{:clj-kondo/ignore [:metabase/validate-deftest]}
(deftest ^:parallel build-before-delete-query-test
  (testing "before-delete's select query should remove `:delete`/`:delete-from` (workaround for https://github.com/camsaul/toucan2/issues/203)"
    (is (= {:select [:*], :from [[:metabase_field :field]], :where [:= :field.id 0]}
           (t2/build
             (t2/select :model/Field
                        {:delete-from [:metabase_field :field]
                         :where       [:= :field.id 0]}))))
    (is (= {:select [:*], :from [[:metabase_field :field]], :where [:= :field.id 0]}
           (t2/build
             (t2/select :model/Field
                        {:delete [:field]
                         :from   [[:metabase_field :field]]
                         :where  [:= :field.id 0]}))))))
