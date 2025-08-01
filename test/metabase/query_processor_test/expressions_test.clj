(ns ^:mb/driver-tests metabase.query-processor-test.expressions-test
  "Tests for expressions (calculated columns)."
  (:require
   [clojure.test :refer :all]
   [java-time.api :as t]
   [medley.core :as m]
   [metabase.driver :as driver]
   [metabase.query-processor :as qp]
   [metabase.test :as mt]
   [metabase.test.data.interface :as tx]
   [metabase.util :as u]
   [metabase.util.date-2 :as u.date]
   [toucan2.core :as t2]))

(deftest ^:parallel basic-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (testing "Do a basic query including an expression"
      (is (= [[1 "Red Medicine"                 4  10.0646 -165.374 3 5.0]
              [2 "Stout Burgers & Beers"        11 34.0996 -118.329 2 4.0]
              [3 "The Apple Pan"                11 34.0406 -118.428 2 4.0]
              [4 "Wurstküche"                   29 33.9997 -118.465 2 4.0]
              [5 "Brite Spot Family Restaurant" 20 34.0778 -118.261 2 4.0]]
             (mt/formatted-rows
              [int str int 4.0 4.0 int float]
              (mt/run-mbql-query venues
                {:expressions {:my_cool_new_field [:+ $price 2]}
                 :limit       5
                 :order-by    [[:asc $id]]})))))))

(deftest ^:parallel floating-point-division-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (testing "Make sure FLOATING POINT division is done"
      (is (= [[1 "Red Medicine"           4 10.0646 -165.374 3 1.5] ; 3 / 2 SHOULD BE 1.5, NOT 1 (!)
              [2 "Stout Burgers & Beers" 11 34.0996 -118.329 2 1.0]
              [3 "The Apple Pan"         11 34.0406 -118.428 2 1.0]]
             (mt/formatted-rows
              [int str int 4.0 4.0 int float]
              (mt/run-mbql-query venues
                {:expressions {:my_cool_new_field [:/ $price 2]}
                 :limit       3
                 :order-by    [[:asc $id]]})))))))

(deftest ^:parallel floating-point-division-for-expressions-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (testing "Make sure FLOATING POINT division is done when dividing by expressions/fields"
      (is (= [[0.6]
              [0.5]
              [0.5]]
             (mt/formatted-rows
              [1.0]
              (mt/run-mbql-query venues
                {:expressions {:big_price         [:+ $price 2]
                               :my_cool_new_field [:/ $price [:expression "big_price"]]}
                 :fields      [[:expression "my_cool_new_field"]]
                 :limit       3
                 :order-by    [[:asc $id]]})))))))

(deftest ^:parallel nested-expressions-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (testing "Can we do NESTED EXPRESSIONS ?"
      (is (= [[1 "Red Medicine"           4 10.0646 -165.374 3 3.0]
              [2 "Stout Burgers & Beers" 11 34.0996 -118.329 2 2.0]
              [3 "The Apple Pan"         11 34.0406 -118.428 2 2.0]]
             (mt/formatted-rows
              [int str int 4.0 4.0 int float]
              (mt/run-mbql-query venues
                {:expressions {:wow [:- [:* $price 2] [:+ $price 0]]}
                 :limit       3
                 :order-by    [[:asc $id]]})))))))

(deftest ^:parallel multiple-expressions-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (testing "Can we have MULTIPLE EXPRESSIONS?"
      (is (= [[1 "Red Medicine"           4 10.0646 -165.374 3 2.0 4.0]
              [2 "Stout Burgers & Beers" 11 34.0996 -118.329 2 1.0 3.0]
              [3 "The Apple Pan"         11 34.0406 -118.428 2 1.0 3.0]]
             (mt/formatted-rows
              [int str int 4.0 4.0 int float float]
              (mt/run-mbql-query venues
                {:expressions {:x [:- $price 1]
                               :y [:+ $price 1]}
                 :limit       3
                 :order-by    [[:asc $id]]})))))))

(deftest ^:parallel expressions-in-fields-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (testing "Can we refer to expressions inside a FIELDS clause?"
      (is (= [[4] [4] [5]]
             (mt/formatted-rows
              [int]
              (mt/run-mbql-query venues
                {:expressions {:x [:+ $price $id]}
                 :fields      [[:expression :x]]
                 :limit       3
                 :order-by    [[:asc $id]]})))))))

(defn- dont-return-expressions-if-fields-is-explicit-query []
  ;; bigquery doesn't let you have hypthens in field, table, etc names
  (let [priceplusone (if (= driver/*driver* :bigquery-cloud-sdk) "price_plus_1" "Price + 1")
        oneplusone   (if (= driver/*driver* :bigquery-cloud-sdk) "one_plus_one" "1 + 1")
        query        (mt/mbql-query venues
                       {:expressions {priceplusone [:+ $price 1]
                                      oneplusone   [:+ 1 1]}
                        :fields      [$price [:expression oneplusone]]
                        :order-by    [[:asc $id]]
                        :limit       3})]
    {:priceplusone priceplusone
     :oneplusone   oneplusone
     :query        query}))

(deftest ^:parallel dont-return-expressions-if-fields-is-explicit-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (let [{:keys [query]} (dont-return-expressions-if-fields-is-explicit-query)]
      (testing "If an explicit `:fields` clause is present, expressions *not* in that clause should not come back"
        (is (= [[3 2] [2 2] [2 2]]
               (mt/formatted-rows
                [int int]
                (qp/process-query query))))))))

(deftest ^:parallel dont-return-expressions-if-fields-is-explicit-test-2
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    ;; bigquery doesn't let you have hypthens in field, table, etc names
    (let [{:keys [query]} (dont-return-expressions-if-fields-is-explicit-query)]
      (testing "If `:fields` is not explicit, then return all the expressions"
        (is (= [[1 "Red Medicine"           4 10.0646 -165.374 3 4 2]
                [2 "Stout Burgers & Beers" 11 34.0996 -118.329 2 3 2]
                [3 "The Apple Pan"         11 34.0406 -118.428 2 3 2]]
               (mt/formatted-rows
                [int str int 4.0 4.0 int int int]
                (qp/process-query (m/dissoc-in query [:query :fields])))))))))

(deftest ^:parallel dont-return-expressions-if-fields-is-explicit-test-3
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    ;; bigquery doesn't let you have hypthens in field, table, etc names
    (let [{:keys [priceplusone oneplusone]} (dont-return-expressions-if-fields-is-explicit-query)]
      (testing "When aggregating, expressions that aren't used shouldn't come back"
        (is (= [[2 22] [3 59] [4 13]]
               (mt/formatted-rows
                [int int]
                (mt/run-mbql-query venues
                  {:expressions {priceplusone [:+ $price 1]
                                 oneplusone   [:+ 1 1]}
                   :aggregation [:count]
                   :breakout    [[:expression priceplusone]]
                   :order-by    [[:asc [:expression priceplusone]]]
                   :limit       3}))))))))

(deftest ^:parallel expressions-in-order-by-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (testing "Can we refer to expressions inside an ORDER BY clause?"
      (is (= [[100 "Mohawk Bend"         46 34.0777 -118.265 2 102.0]
              [99  "Golden Road Brewing" 10 34.1505 -118.274 2 101.0]
              [98  "Lucky Baldwin's Pub"  7 34.1454 -118.149 2 100.0]]
             (mt/formatted-rows
              [int str int 4.0 4.0 int float]
              (mt/run-mbql-query venues
                {:expressions {:x [:+ $price $id]}
                 :limit       3
                 :order-by    [[:desc [:expression :x]]]})))))))

(deftest ^:parallel expressions-in-order-by-test-2
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (testing "Can we refer to expressions inside an ORDER BY clause with a secondary order by?"
      (is (= [[81 "Tanoshi Sushi & Sake Bar" 40 40.7677 -73.9533 4 85.0]
              [79 "Sushi Yasuda" 40 40.7514 -73.9736 4 83.0]
              [77 "Sushi Nakazawa" 40 40.7318 -74.0045 4 81.0]]
             (mt/formatted-rows
              [int str int 4.0 4.0 int float]
              (mt/run-mbql-query venues
                {:expressions {:x [:+ $price $id]}
                 :limit       3
                 :order-by    [[:desc $price] [:desc [:expression :x]]]})))))))

(deftest ^:parallel aggregate-breakout-expression-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (testing "Can we AGGREGATE + BREAKOUT by an EXPRESSION?"
      (is (= [[2 22] [4 59] [6 13] [8 6]]
             (mt/formatted-rows
              [int int]
              (mt/run-mbql-query venues
                {:expressions {:x [:* $price 2.0]}
                 :aggregation [[:count]]
                 :breakout    [[:expression :x]]})))))))

(deftest ^:parallel expressions-should-include-type-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (testing "Custom aggregation expressions should include their type"
      (let [cols (mt/cols
                  (mt/run-mbql-query venues
                    {:aggregation [[:aggregation-options [:sum [:* $price -1]] {:name "x"}]]
                     :breakout    [$category_id]}))]
        (testing (format "cols = %s" (u/pprint-to-str cols))
          (is (= #{"x" (mt/format-name "category_id")}
                 (set (map :name cols))))
          (let [name->base-type (into {} (map (juxt :name :base_type) cols))]
            (testing "x"
              (is (isa? (name->base-type "x")
                        :type/Number)))
            (testing "category_id"
              (is (isa? (name->base-type (mt/format-name "category_id"))
                        :type/Number)))))))))

(defn- calculate-bird-scarcity*
  "\"bird scarcity\" is a \"scientific metric\" based on the number of birds seen in a given day (at least for the
  purposes of the tests below).

  e.g.

    scarcity = 100.0 / num-birds"
  [formula filter-clause]
  (mt/formatted-rows
   [2.0]
   (mt/dataset daily-bird-counts
     (mt/run-mbql-query bird-count
       {:expressions {"bird-scarcity" formula}
        :fields      [[:expression "bird-scarcity"]]
        :filter      filter-clause
        :order-by    [[:asc $date]]
        :limit       10}))))

(defmacro ^:private calculate-bird-scarcity [formula & [filter-clause]]
  `(mt/dataset ~'daily-bird-counts
     (mt/$ids ~'bird-count
       (calculate-bird-scarcity* ~formula ~filter-clause))))

(defn- nulls-and-zeroes-test-drivers []
  (disj (mt/normal-drivers-with-feature :expressions)
        ;; bigquery doesn't let you have hypthens in field, table, etc names
        ;; therefore a different macro is tested in bigquery driver tests
        :bigquery-cloud-sdk))

(deftest ^:parallel nulls-and-zeroes-test-1
  (mt/test-drivers (nulls-and-zeroes-test-drivers)
    (testing (str "hey... expressions should work if they are just a Field! (Also, this lets us take a peek at the "
                  "raw values being used to calculate the formulas below, so we can tell at a glance if they're right "
                  "without referring to the EDN def)")
      (is (= [[nil] [0.0] [0.0] [10.0] [8.0] [5.0] [5.0] [nil] [0.0] [0.0]]
             (calculate-bird-scarcity $count))))))

(deftest ^:parallel nulls-and-zeroes-test-2
  (mt/test-drivers (nulls-and-zeroes-test-drivers)
    (testing (str "do expressions automatically handle division by zero? Should return `nil` "
                  "in the results for places where that was attempted")
      (is (= [[nil] [nil] [10.0] [12.5] [20.0] [20.0] [nil] [nil] [9.09] [7.14]]
             (calculate-bird-scarcity [:/ 100.0 $count] [:!= $count nil]))))))

(deftest ^:parallel nulls-and-zeroes-test-3
  (mt/test-drivers (nulls-and-zeroes-test-drivers)
    (testing (str
              "do expressions handle division by `nil`? Should return `nil` in the results for places where that "
              "was attempted")
      (is (= [[nil] [10.0] [12.5] [20.0] [20.0] [nil] [9.09] [7.14] [12.5] [7.14]]
             (calculate-bird-scarcity [:/ 100.0 $count] [:or [:= $count nil] [:!= $count 0]]))))))

(deftest ^:parallel nulls-and-zeroes-test-4
  (mt/test-drivers
    (nulls-and-zeroes-test-drivers)
    (testing
     "can we handle BOTH NULLS AND ZEROES AT THE SAME TIME????"
      (is
       (=
        [[nil] [nil] [nil] [10.0] [12.5] [20.0] [20.0] [nil] [nil] [nil]]
        (calculate-bird-scarcity [:/ 100.0 $count]))))))
(deftest ^:parallel nulls-and-zeroes-test-5
  (mt/test-drivers (nulls-and-zeroes-test-drivers)
    (testing "can we handle dividing by literal 0?"
      (is (= [[nil] [nil] [nil] [nil] [nil] [nil] [nil] [nil] [nil] [nil]]
             (calculate-bird-scarcity [:/ $count 0]))))))

(deftest ^:parallel nulls-and-zeroes-test-6
  (mt/test-drivers (nulls-and-zeroes-test-drivers)
    (testing "ok, what if we use multiple args to divide, and more than one is zero?"
      (is (= [[nil] [nil] [nil] [1.0] [1.56] [4.0] [4.0] [nil] [nil] [nil]]
             (calculate-bird-scarcity [:/ 100.0 $count $count]))))))

(deftest ^:parallel nulls-and-zeroes-test-7
  (mt/test-drivers (nulls-and-zeroes-test-drivers)
    (testing "are nulls/zeroes still handled appropriately when nested inside other expressions?"
      (is (= [[nil] [nil] [nil] [20.0] [25.0] [40.0] [40.0] [nil] [nil] [nil]]
             (calculate-bird-scarcity [:* [:/ 100.0 $count] 2]))))))

(deftest ^:parallel nulls-and-zeroes-test-8
  (mt/test-drivers (nulls-and-zeroes-test-drivers)
    (testing (str
              "if a zero is present in the NUMERATOR we should return ZERO and not NULL "
              "(`0 / 10 = 0`; `10 / 0 = NULL`, at least as far as MBQL is concerned)")
      (is (= [[nil] [0.0] [0.0] [1.0] [0.8] [0.5] [0.5] [nil] [0.0] [0.0]]
             (calculate-bird-scarcity [:/ $count 10]))))))

(deftest ^:parallel nulls-and-zeroes-test-9
  (mt/test-drivers (nulls-and-zeroes-test-drivers)
    (testing "can addition handle nulls & zeroes?"
      (is (= [[nil] [10.0] [10.0] [20.0] [18.0] [15.0] [15.0] [nil] [10.0] [10.0]]
             (calculate-bird-scarcity [:+ $count 10]))))))

(deftest ^:parallel nulls-and-zeroes-test-10
  (mt/test-drivers (nulls-and-zeroes-test-drivers)
    (testing "can subtraction handle nulls & zeroes?"
      (is (= [[nil] [10.0] [10.0] [0.0] [2.0] [5.0] [5.0] [nil] [10.0] [10.0]]
             (calculate-bird-scarcity [:- 10 $count]))))))

(deftest ^:parallel nulls-and-zeroes-test-11
  (mt/test-drivers (nulls-and-zeroes-test-drivers)
    (testing "can multiplications handle nulls & zeros?"
      (is (= [[nil] [0.0] [0.0] [10.0] [8.0] [5.0] [5.0] [nil] [0.0] [0.0]]
             (calculate-bird-scarcity [:* 1 $count]))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                      DATETIME EXTRACTION AND MANIPULATION                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- robust-dates
  [strs]
  ;; TIMEZONE FIXME — SQLite shouldn't return strings.
  (let [format-fn (if (= driver/*driver* :sqlite)
                    #(u.date/format-sql (t/local-date-time %))
                    u.date/format)]
    (for [s strs]
      [(format-fn (u.date/parse s "UTC"))])))

(deftest temporal-arithmetic-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions :date-arithmetics)
    (doseq [[op interval] [[:+ [:interval -31 :day]]
                           [:- [:interval 31 :day]]]]
      (testing (str "Test that we can do datetime arithemtics using " op " and MBQL `:interval` clause in expressions")
        (is (= (robust-dates
                ["2014-09-02T13:45:00"
                 "2014-07-02T09:30:00"
                 "2014-07-01T10:30:00"])
               (mt/with-temporary-setting-values [report-timezone "UTC"]
                 (-> (mt/run-mbql-query users
                       {:expressions {:prev_month [op $last_login interval]}
                        :fields      [[:expression :prev_month]]
                        :limit       3
                        :order-by    [[:asc $name]]})
                     mt/rows))))))))

(deftest temporal-arithmetic-test-2
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions :date-arithmetics)
    (doseq [[op interval] [[:+ [:interval -31 :day]]
                           [:- [:interval 31 :day]]]]
      (testing (str "Test interaction of datetime arithmetics with truncation using " op " operator")
        (is (= (robust-dates
                ["2014-09-02T00:00:00"
                 "2014-07-02T00:00:00"
                 "2014-07-01T00:00:00"])
               (mt/with-temporary-setting-values [report-timezone "UTC"]
                 (-> (mt/run-mbql-query users
                       {:expressions {:prev_month [op !day.last_login interval]}
                        :fields      [[:expression :prev_month]]
                        :limit       3
                        :order-by    [[:asc $name]]})
                     mt/rows))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                WEEKDAYS                                                        |
;;; +----------------------------------------------------------------------------------------------------------------+
;;; Background on weekdays in Metabase:
;;; - Day 1 inside Metabase is defined by [[metabase.lib-be.core/start-of-week]]; default `:sunday`.
;;; - Databases store this differently - 1 to 7, 0 to 6, hard-coded first day, based on the locale, ...
;;; - Drivers handle that variation, and always expect 1 to 7 where 1 is the `start-of-week` day.
;;; - Locales differ in what they consider the first day of the week; generally Sunday in the Americas, Monday in
;;;   Europe, and Saturday in the Arabic-speaking world.
;;;
;;; Goals:
;;; - `[:get-day-of-week "2024-04-19"]` returns numbers 1-7, consistent with the `start-of-week` setting.
;;; - `[:day-name 4]` understands `4` based on `start-of-week`, and the correct *user* locale name is returned
;;;   - Even if the user locale and the Metabase setting disagree about which is day 1!
;;; - Site locale is irrelevant here.

;;; In these tests, we have a series of specific dates from the checkins table that happen to run Monday to Sunday.
;;; So in the query results:
;;; - The **order** of the days is fixed, since we're sorting by date ascending.
;;; - The **day numbers** are based on the `start-of-week` setting.
;;; - The **day names** are based on the user locale.

(def ^:private weekdays-english
  ["Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"])

(def ^:private weekdays-spanish
  ["lunes" "martes" "miércoles" "jueves" "viernes" "sábado" "domingo"])

(deftest ^:synchronized weekday-numbers-and-names-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (doseq [first-day                [:sunday :monday :saturday]
            ;; Adjusting the site locale to get different languages and first day of the week. It should be ignored!
            site-locale              ["en_US" "es_ES"]
            ;; For each of two languages and two regions, we check that:
            ;; 1. the locale's first day of the week is ignored, and
            ;; 2. the names are correctly translated for the *user* locale.
            [user-locale exp-names] [;; US uses Sunday=1 in both English and Spanish.
                                     ["en_US" weekdays-english]
                                     ["es_US" weekdays-spanish]
                                     ;; Europe uses Monday=1 in both English and Spanish.
                                     ["en_UK" weekdays-english]
                                     ["es_ES" weekdays-spanish]]]
      ;; Metabase queries should return weekday numbers based on the *setting*, not the locales.
      ;; This fixed set of dates from checkins runs Monday to Sunday when sorted ascending by date.
      (let [known-week  ["2013-02-18"   ; Monday
                         "2013-02-19"   ; Tuesday
                         "2013-02-20"   ; Wednesday
                         "2013-02-21"   ; Thursday
                         "2013-02-22"   ; Friday
                         "2013-03-16"   ; Saturday
                         "2013-03-16"   ; Saturday
                         "2013-04-28"]  ; Sunday
            ;; The absolute weekdays are fixed above, but the numbering depends on the `start-of-week` setting.
            exp-numbers (case first-day
                          :saturday [3 4 5 6 7 1 2]
                          :sunday   [2 3 4 5 6 7 1]
                          :monday   [1 2 3 4 5 6 7])]
        (mt/with-temporary-setting-values [start-of-week first-day
                                           site-locale   site-locale]
          (mt/with-user-locale user-locale
            ;; Fetching [number name date].
            (let [results (mt/formatted-rows
                           [int str]
                           (mt/run-mbql-query checkins
                             {:fields      [[:expression "weekday"] [:expression "name"]]
                              :expressions {:weekday [:get-day-of-week $date]
                                            :name    [:day-name [:expression "weekday"]]}
                              :filter      (into [:= $date] known-week)
                              :order-by    [[:asc $date]]}))]
              (testing "weekday numbers disregard site and user locales, and respect `start-of-week` setting"
                (is (=? exp-numbers (map first results))))
              (testing "weekday names are correctly translated by *user* locale, though weekday differs across locales"
                (is (=? exp-names   (map second results)))))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                     JOINS                                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

(deftest ^:parallel expressions+joins-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions :left-join :date-arithmetics)
    (testing "Do calculated columns play well with joins"
      (is (= "Simcha Yan"
             (-> (mt/run-mbql-query checkins
                   {:expressions {:prev_month [:+ $date [:interval -31 :day]]}
                    :fields      [[:field (mt/id :users :name) {:join-alias "users__via__user_id"}]
                                  [:expression :prev_month]]
                    :limit       1
                    :order-by    [[:asc $date]]
                    :joins       [{:strategy :left-join
                                   :source-table (mt/id :users)
                                   :alias        "users__via__user_id"
                                   :condition    [:=
                                                  $user_id
                                                  [:field (mt/id :users :id) {:join-alias "users__via__user_id"}]]}]})
                 mt/rows
                 ffirst))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                               LITERAL EXPRESSIONS                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private standard-literal-expression-defs
  {"foo"      [:value "foo" {:base_type :type/Text}]
   "zero"     [:value 0     {:base_type :type/Integer}]
   "12345"    [:value 12345 {:base_type :type/Integer}]
   "float"    [:value 1.234 {:base_type :type/Float}]
   "MyTrue"   [:value true  {:base_type :type/Boolean}]
   "MyFalse"  [:value false {:base_type :type/Boolean}]})

(def ^:private standard-literal-expression-refs
  [[:expression "foo"]
   [:expression "zero"]
   [:expression "12345"]
   [:expression "float"]
   [:expression "MyTrue"]
   [:expression "MyFalse"]])

(def ^:private standard-literal-expression-column-refs
  [[:field "foo"     {:base_type :type/Text}]
   [:field "zero"    {:base_type :type/Integer}]
   [:field "12345"   {:base_type :type/Integer}]
   [:field "float"   {:base_type :type/Float}]
   [:field "MyTrue"  {:base_type :type/Boolean}]
   [:field "MyFalse" {:base_type :type/Boolean}]])

(def ^:private standard-literal-expression-row-formats
  [str int int 3.0 mt/boolish->bool mt/boolish->bool])

(def ^:private standard-literal-expression-row-formats-with-id
  (into [int] standard-literal-expression-row-formats))

(def ^:private standard-literal-expression-values
  (map second (vals standard-literal-expression-defs)))

(deftest ^:parallel basic-literal-expression-test
  (testing "basic literal expressions"
    (mt/test-drivers (mt/normal-drivers-with-feature :expressions :expression-literals)
      (is (= [(into [1] standard-literal-expression-values)]
             (mt/formatted-rows
              standard-literal-expression-row-formats-with-id
              (mt/run-mbql-query orders
                {:expressions standard-literal-expression-defs
                 :fields      (into [$id] standard-literal-expression-refs)
                 :order-by    [[:asc  $id]]
                 :limit       1})))))))

(deftest ^:parallel filter-literal-expression-with-=-!=-test
  (doseq [[and-or eq-ne expected] [[:and :=  [standard-literal-expression-values]]
                                   [:or  :!= []]]]
    (testing (str "filter literal expressions with " and-or " " eq-ne)
      (mt/test-drivers (mt/normal-drivers-with-feature :expressions :expression-literals)
        (is (= expected
               (mt/formatted-rows
                standard-literal-expression-row-formats
                (mt/run-mbql-query orders
                  {:expressions standard-literal-expression-defs
                   :fields      standard-literal-expression-refs
                   :filter      (into [and-or] (map #(vector eq-ne % %) standard-literal-expression-refs))
                   :limit       1}))))))))

(deftest ^:parallel nested-literal-expression-test
  (testing "nested literal expression"
    (mt/test-drivers (mt/normal-drivers-with-feature :expressions :expression-literals :nested-queries)
      (is (= [(into [1] standard-literal-expression-values)]
             (mt/formatted-rows
              standard-literal-expression-row-formats-with-id
              (mt/run-mbql-query venues
                {:fields       (into [$id] standard-literal-expression-column-refs)
                 :source-query {:source-table $$venues
                                :expressions  standard-literal-expression-defs
                                :fields       (into [$id] standard-literal-expression-refs)}
                 :order-by     [[:asc $id]]
                 :limit        1})))))))

(deftest ^:parallel order-by-integer-literal-expression-test
  (testing "order-by integer literal expression"
    ;; Verify that :order-by of [:expression "One"] does NOT mean ORDER BY 1, which would result in ordering by the
    ;; first column in the select list $id. We want this to mean "order by the expression with value 1".
    (mt/test-drivers (mt/normal-drivers-with-feature :expressions :expression-literals :nested-queries)
      (is (= [[29 1 "20th Century Cafe"]
              [8  1 "25°"]]
             (mt/formatted-rows
              [int int str]
              (mt/run-mbql-query venues
                {:expressions {"One" [:value 1 {:base_type :type/Integer}]}
                 :fields      [$id [:expression "One"] $name]
                 :order-by    [[:asc [:expression "One"]]
                               [:asc $name]]
                 :limit       2})))))))

(deftest ^:parallel order-by-literal-expression-test
  (testing "order-by all literal expression types"
    (mt/test-drivers (mt/normal-drivers-with-feature :expressions :expression-literals :nested-queries)
      (is (= [(into [1] standard-literal-expression-values)
              (into [2] standard-literal-expression-values)]
             (mt/formatted-rows
              standard-literal-expression-row-formats-with-id
              (mt/run-mbql-query orders
                {:expressions standard-literal-expression-defs
                 :fields      (into [$id] standard-literal-expression-refs)
                 :order-by    (into [[:asc  $id]]
                                    (map #(vector :asc %) standard-literal-expression-refs))
                 :limit       2})))))))

(deftest ^:parallel breakout-by-literal-expression-test
  (testing "breakout by all literal expression types"
    (mt/test-drivers (mt/normal-drivers-with-feature :expression-literals :basic-aggregations)
      (let [orders-count 18760]
        (is (= [(conj (vec standard-literal-expression-values) orders-count)]
               (mt/formatted-rows
                (conj standard-literal-expression-row-formats int)
                (mt/run-mbql-query orders
                  {:expressions standard-literal-expression-defs
                   :aggregation [:count]
                   :breakout    standard-literal-expression-refs}))))))))

(deftest ^:parallel case-with-literal-expression-test
  (testing "CASE expression using literal expressions"
    (mt/test-drivers (mt/normal-drivers-with-feature :expressions :expression-literals)
      (is (= [[1 12345 true  "foobar"]
              [2 12345 false "foobar"]]
             (mt/formatted-rows
              [int int mt/boolish->bool str]
              (mt/run-mbql-query venues
                {:expressions (into standard-literal-expression-defs
                                    {"case 1" [:case
                                               [[[:< [:expression "zero"] 0]
                                                 [:expression "zero"]]
                                                [[:expression "MyFalse"]
                                                 [:expression "zero"]]
                                                [[:= "foo" [:expression "foo"]]
                                                 [:expression "12345"]]]
                                               {:default [:expression "zero"]}]
                                     "case 2" [:case
                                               [[[:= $id 1]
                                                 [:expression "MyTrue"]]
                                                [[:= $id 2]
                                                 [:expression "MyFalse"]]]]
                                     "case 3" [:case
                                               [[[:= [:concat [:expression "foo"] ""] "bar"]
                                                 [:expression "foo"]]
                                                [[:> [:expression "zero"] 0]
                                                 [:expression "foo"]]
                                                [[:is-null [:expression "foo"]]
                                                 [:expression "foo"]]]
                                               {:default [:concat [:expression "foo"] "bar"]}]})
                 :fields      [$id
                               [:expression "case 1"]
                               [:expression "case 2"]
                               [:expression "case 3"]]
                 :order-by    [[:asc $id]]
                 :limit       2})))))))

(deftest ^:parallel sum-where-with-literal-expression-test
  (testing "sum-where using literal expressions"
    (mt/test-drivers (mt/normal-drivers-with-feature :basic-aggregations :expressions :expression-literals)
      (is (= [[1510617.7 0.0]]
             (mt/formatted-rows
              [2.0 2.0]
              (mt/run-mbql-query orders
                {:expressions standard-literal-expression-defs
                 :aggregation [[:sum-where $total [:expression "MyTrue"]]
                               [:sum-where $total [:expression "MyFalse"]]]})))))))

(deftest ^:parallel count-where-with-literal-expression-test
  (testing "count-where using literal expressions"
    (mt/test-drivers (mt/normal-drivers-with-feature :basic-aggregations :expressions :expression-literals)
      (is (= [[18760 0]]
             (mt/formatted-rows
              [int int]
              (mt/run-mbql-query orders
                {:expressions standard-literal-expression-defs
                 :aggregation [[:count-where [:expression "MyTrue"]]
                               [:count-where [:expression "MyFalse"]]]})))))))

(deftest ^:parallel distinct-where-with-literal-expression-test
  (testing "distinct-where using literal expressions"
    (mt/test-drivers (mt/normal-drivers-with-feature :distinct-where :expressions :expression-literals)
      (is (= [[1746 0]]
             (mt/formatted-rows
              [int int]
              (mt/run-mbql-query orders
                {:expressions standard-literal-expression-defs
                 :aggregation [[:distinct-where $user_id [:expression "MyTrue"]]
                               [:distinct-where $user_id [:expression "MyFalse"]]]})))))))

(deftest ^:parallel filter-literal-expression-with-and-or-test
  (doseq [[op expected] [[:and []]
                         [:or  [[true false]]]]]
    (testing (str "filter literal expressions with " op)
      (mt/test-drivers (mt/normal-drivers-with-feature :expressions :expression-literals)
        (is (= expected
               (mt/formatted-rows
                [mt/boolish->bool mt/boolish->bool]
                (mt/run-mbql-query orders
                  {:expressions {"MyTrue"  [:value true  {:base_type :type/Boolean}]
                                 "MyFalse" [:value false {:base_type :type/Boolean}]}
                   :fields      [[:expression "MyTrue"]
                                 [:expression "MyFalse"]]
                   :filter      (into [op] [[:expression "MyTrue"]
                                            [:expression "MyFalse"]])
                   :limit       1}))))))))

(deftest ^:parallel filter-literal-boolean-expression-with-no-operator-test
  (doseq [[expression expected] [[[:value true nil]       [standard-literal-expression-values]]
                                 [[:value false nil]      []]
                                 [[:expression "MyTrue"]  [standard-literal-expression-values]]
                                 [[:expression "MyFalse"] []]]]
    (testing (str "filter literal expressions with " expression)
      (mt/test-drivers (mt/normal-drivers-with-feature :expressions :expression-literals)
        (is (= expected
               (mt/formatted-rows
                standard-literal-expression-row-formats
                (mt/run-mbql-query orders
                  {:expressions standard-literal-expression-defs
                   :fields      standard-literal-expression-refs
                   :filter      expression
                   :limit       1}))))))))

(deftest ^:parallel empty-string-literal-expression-test
  (testing "empty string as literal expression"
    (mt/test-drivers (mt/normal-drivers-with-feature :expressions :expression-literals)
      (is (= [[""]]
             (mt/formatted-rows
              [str]
              #_format-nil-values? true
              (mt/run-mbql-query orders
                {:expressions {"empty" [:value "" {:base_type :type/Text}]}
                 :fields      [[:expression "empty"]]
                 :limit       1})))))))

(deftest ^:parallel nested-and-filtered-literal-expression-test
  (testing "nested and filtered literal expression"
    (mt/test-drivers (mt/normal-drivers-with-feature :expressions :expression-literals :nested-queries)
      (is (= [[2 "Stout Burgers & Beers" true "Red Medicine" 1 2 "Bob's Burgers"]
              [3 "The Apple Pan" true "Red Medicine" 1 2 "Bob's Burgers"]
              [4 "Wurstküche" true "Red Medicine" 1 2 "Bob's Burgers"]]
             (mt/formatted-rows
              [int str mt/boolish->bool str int int str]
              (mt/run-mbql-query venues
                {:fields       [$id
                                $name
                                [:expression "MyTrue"]
                                [:expression "Name"]
                                *One/Integer
                                *Two/Integer
                                *Bob/Text]
                 :expressions  {"MyTrue"  [:value true {:base_type :type/Boolean}]
                                "Name"    [:value "Red Medicine" {:base_type :type/Text}]}
                 :source-query {:source-table $$venues
                                :fields       [$id
                                               $name
                                               [:expression "One"]
                                               [:expression "Two"]
                                               [:expression "Bob"]]
                                :expressions  {"One" [:value 1.0 {:base_type :type/Float}]
                                               "Two" [:value 2 {:base_type :type/Integer}]
                                               "Bob" [:value "Bob's Burgers" {:base_type :type/Text}]}
                                :filter       [:= 2.0 [:* [:expression "Two"] [:expression "One"]]]}
                 :filter       [:and
                                [:!= *Bob/Text [:expression "Name"]]
                                [:!= $name [:expression "Name"]]
                                [:= true [:expression "MyTrue"]]
                                [:= [:expression "MyTrue"] true]
                                [:=
                                 [:expression "Name"]
                                 [:concat [:expression "Name"] ""]]]
                 :order-by     [[:asc $id]]
                 :limit        3})))))))

(deftest ^:parallel joined-literal-expression-test
  (testing "joined literal expression"
    (mt/test-drivers (mt/normal-drivers-with-feature :expressions :expression-literals :left-join :nested-queries)
      (is (= [[2 "Stout Burgers & Beers" 2 0.5 1 "Stout Burgers & Beers" "25°"]
              [2 "Stout Burgers & Beers" 2 0.5 1 "Stout Burgers & Beers" "In-N-Out Burger"]
              [2 "Stout Burgers & Beers" 2 0.5 1 "Stout Burgers & Beers" "The Apple Pan"]]
             (mt/formatted-rows
              [int str int 1.0 int str str]
              (mt/run-mbql-query venues
                {:fields      [$id
                               $name
                               $price
                               [:expression "InversePrice"]
                               &JoinedCategories.*LiteralInt/Integer
                               &JoinedCategories.*LiteralString/Text
                               &JoinedCategories.venues.name]
                 :expressions {"InversePrice"  [:/ &JoinedCategories.*LiteralInt/Integer $price]
                               "NameEquals"    [:= &JoinedCategories.*LiteralString/Text $name]}
                 :filter      [:expression "NameEquals"]
                 :joins       [{:strategy     :left-join
                                :condition    [:= $category_id &JoinedCategories.venues.category_id]
                                :source-query {:source-table $$venues
                                               :expressions  {"LiteralInt"    [:value 1 {:base_type :type/Integer}]
                                                              "LiteralString" [:value "Stout Burgers & Beers" {:base_type :type/Text}]}
                                               :filter       [:!= $name [:expression "LiteralString"]]
                                               :fields       [$category_id
                                                              $name
                                                              [:expression "LiteralInt"]
                                                              [:expression "LiteralString"]]
                                               :order-by     [[:asc $category_id]
                                                              [:asc $id]]}
                                :alias        "JoinedCategories"}]
                 :order-by    [[:asc &JoinedCategories.venues.name]]
                 :limit       3})))))))

(deftest ^:parallel literal-expressions-inside-nested-and-filtered-aggregations-test
  (testing "nested aggregated and filtered literal expression"
    (mt/test-drivers (mt/normal-drivers-with-feature :basic-aggregations :expressions :expression-literals :nested-queries)
      (is (= [[2 true "Red Medicine" 1 8 16]
              [3 true "Red Medicine" 1 2 4]
              [4 true "Red Medicine" 1 2 4]]
             (mt/formatted-rows
              [int mt/boolish->bool str int int int]
              (mt/run-mbql-query venues
                {:fields       [$category_id
                                [:expression "True"]
                                [:expression "Name"]
                                *AvgOne/Integer
                                *SumOne/Integer
                                *SumTwo/Integer]
                 :expressions  {"True"  [:value true {:base_type :type/Boolean}]
                                "Name"  [:value "Red Medicine" {:base_type :type/Text}]}
                 :source-query {:source-table $$venues
                                :expressions  {"One" [:value 1.0 {:base_type :type/Float}]
                                               "Two" [:value 2 {:base_type :type/Integer}]
                                               "Bob" [:value "Bob's Burgers" {:base_type :type/Text}]}
                                :aggregation  [[:aggregation-options [:avg [:expression "One"]] {:name "AvgOne"}]
                                               [:aggregation-options [:sum [:expression "One"]] {:name "SumOne"}]
                                               [:aggregation-options [:sum [:expression "Two"]] {:name "SumTwo"}]
                                               [:aggregation-options [:min [:expression "Bob"]] {:name "MinBob"}]]
                                :breakout     [$category_id]
                                :filter       [:= 2.0 [:* [:expression "Two"] [:expression "One"]]]}
                 :filter       [:and
                                [:!= *MinBob/Text [:expression "Name"]]
                                [:= true [:expression "True"]]
                                [:= [:expression "True"] true]
                                [:=
                                 [:expression "Name"]
                                 [:concat [:expression "Name"] ""]]]
                 :order-by     [[:asc $category_id]]
                 :limit        3})))))))

(deftest ^:parallel literal-expressions-inside-joined-aggregations-test
  (testing "joined and aggregated literal expression"
    (mt/test-drivers (mt/normal-drivers-with-feature
                      :basic-aggregations
                      :expression-literals
                      :left-join
                      :nested-queries)
      (is (= [[1 "Red Medicine" 3 0.33 1]
              [2 "Stout Burgers & Beers" 2 0.50 1]
              [3 "The Apple Pan" 2 0.5 1]]
             (mt/formatted-rows
              [int str int 2.0 int]
              (mt/run-mbql-query venues
                {:fields      [$id
                               $name
                               $price
                               [:expression "InversePrice"]
                               &JoinedCategories.*MaxOne/Integer]
                 :expressions {"InversePrice"  [:/ &JoinedCategories.*MaxOne/Integer $price]}
                 :joins       [{:strategy     :left-join
                                :condition    [:= $category_id &JoinedCategories.venues.category_id]
                                :source-query {:source-table $$venues
                                               :expressions  {"One" [:value 1 {:base_type :type/Integer}]}
                                               :aggregation  [[:aggregation-options [:max [:expression "One"]] {:name "MaxOne"}]]
                                               :breakout     [$category_id]}
                                :alias        "JoinedCategories"}]
                 :order-by    [[:asc $id]]
                 :limit       3})))))))

(deftest ^:parallel literal-boolean-expressions-and-fields-in-conditions-test
  (testing "literal boolean expression and field references in filter and case clauses"
    ;; Test boolean->comparison conversion for drivers that need it. See boolean_to_comparison.clj. Other drivers
    ;; should support these queries without rewriting top-level booleans in conditions.
    (let [true-value  (get standard-literal-expression-defs "MyTrue")
          false-value (get standard-literal-expression-defs "MyFalse")]
      (mt/test-drivers (mt/normal-drivers-with-feature :basic-aggregations :expressions :expression-literals :nested-queries)
        (mt/dataset places-cam-likes
          (is (= [[3 0 2 2]]
                 (mt/formatted-rows
                  [int int int int]
                  (mt/run-mbql-query places
                    {:expressions  {"T" true-value, "F" false-value}
                     :source-query {:source-table $$places
                                    :fields [$liked]}
                     :aggregation  [[:count-where [:expression "T"]]
                                    [:count-where [:expression "F"]]
                                    [:count-where *liked]
                                    [:count-where $liked]]
                     :filter       [:or
                                    $liked ; id-based field ref
                                    *liked ; named-based field ref
                                    [:expression "T"]]})))))))))

(deftest ^:parallel nested-and-joined-literal-boolean-expressions-in-conditions-test
  (testing "nested and joined literal boolean expression references in filter and case clauses"
    ;; Test boolean->comparison conversion for drivers that need it. See boolean_to_comparison.clj. Other drivers
    ;; should support these queries without rewriting top-level booleans in conditions.
    (let [true-value  (get standard-literal-expression-defs "MyTrue")
          false-value (get standard-literal-expression-defs "MyFalse")]
      (mt/test-drivers (mt/normal-drivers-with-feature
                        :basic-aggregations
                        :expressions
                        :expression-literals
                        :left-join
                        :nested-queries)
        (mt/dataset places-cam-likes
          (is (= [[3 0 0]]
                 (mt/formatted-rows
                  [int int int]
                  (mt/run-mbql-query nil
                    {:expressions  {"T0" true-value, "F0" false-value}
                     :source-query {:source-query {:source-table $$places
                                                   :expressions  {"T2" true-value, "F2" false-value}
                                                   :fields       [$places.id [:expression "T2"] [:expression "F2"]]
                                                   :filter       [:or [:expression "F2"] [:expression "T2"]]}
                                    :expressions  {"T1" true-value, "F1" false-value}
                                    :fields       [$places.id
                                                   [:expression "T1"]
                                                   [:expression "F1"]
                                                   *T2/Boolean
                                                   *F2/Boolean]
                                    :filter       [:and
                                                   [:expression "T1"]
                                                   [:or
                                                    [:expression "F1"]
                                                    *F2/Boolean
                                                    *T2/Boolean]]}
                     :joins        [{:strategy     :left-join
                                     :condition    [:= $places.id &Joined.places.id]
                                     :source-query {:source-table $$places
                                                    :expressions  {"TJ" true-value, "FJ" false-value}
                                                    :fields       [$places.id [:expression "TJ"] [:expression "FJ"]]}
                                     :alias        "Joined"}]
                     :aggregation  [[:count-where *T2/Boolean]
                                    [:count-where *F1/Boolean]
                                    [:count-where &Joined.*FJ/Boolean]]
                     :filter       [:and
                                    &Joined.*TJ/Boolean
                                    [:expression "T0"]
                                    [:or *T1/Boolean *F2/Boolean &Joined.*FJ/Boolean]]
                     :limit        1})))))))))

(deftest ^:parallel nested-literal-boolean-expression-with-name-collisions-in-conditions-test
  (testing "nested literal boolean expression references with name collisions in filter and case clauses"
    ;; Test boolean->comparison conversion for drivers that need it. See boolean_to_comparison.clj. Other drivers
    ;; should support these queries without rewriting top-level booleans in conditions.
    (let [true-value  (get standard-literal-expression-defs "MyTrue")
          false-value (get standard-literal-expression-defs "MyFalse")]
      (mt/test-drivers (mt/normal-drivers-with-feature :basic-aggregations :expressions :expression-literals :nested-queries)
        (is (= [[18760 0]]
               (mt/formatted-rows
                [int int]
                (mt/run-mbql-query nil
                  {:expressions  {"T" true-value, "F" false-value}
                   :source-query {:source-query {:source-table $$orders
                                                 :expressions  {"T" true-value, "F" false-value}
                                                 :fields       [[:expression "T"] [:expression "F"]]}
                                  :expressions  {"T" true-value, "F" false-value}
                                  :fields       [[:expression "T"]
                                                 [:expression "F"]
                                                 *T/Boolean
                                                 *F/Boolean]}
                   :aggregation  [[:count-where *T/Boolean]
                                  [:count-where *F/Boolean]]
                   :filter       [:and
                                  [:or [:expression "T"] [:expression "F"]]
                                  [:or *T/Boolean *F/Boolean]]
                   :limit        1}))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                 MISC BUG FIXES                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

;; need more fields than seq chunking size
(defrecord ^:private NoLazinessDatasetDefinition [num-fields])

(defn- no-laziness-dataset-definition-field-names [num-fields]
  (for [i (range num-fields)]
    (format "field_%04d" i)))

(defmethod mt/get-dataset-definition NoLazinessDatasetDefinition
  [{:keys [num-fields]}]
  (mt/dataset-definition
   (format "no-laziness-%d" num-fields)
   [["lots-of-fields"
     (concat
      [{:field-name "a", :base-type :type/Integer}
       {:field-name "b", :base-type :type/Integer}]
      (for [field (no-laziness-dataset-definition-field-names num-fields)]
        {:field-name (name field), :base-type :type/Integer}))
     ;; one row
     [(range (+ num-fields 2))]]]))

(defn- no-laziness-dataset-definition [num-fields]
  (->NoLazinessDatasetDefinition num-fields))

;; Make sure no part of query compilation is lazy as that won't play well with dynamic bindings.
;; This is not an issue limited to expressions, but using expressions is the most straightforward
;; way to reproducing it.
(deftest ^:parallel no-lazyness-test
  ;; Sometimes Kondo thinks this is unused, depending on the state of the cache -- see comments in
  ;; [[hooks.metabase.test.data]] for more information. It's definitely used to.
  #_{:clj-kondo/ignore [:unused-binding]}
  (let [dataset-def (no-laziness-dataset-definition 300)]
    (mt/dataset dataset-def
      (let [query (mt/mbql-query lots-of-fields
                    {:expressions {:c [:+
                                       [:field (mt/id :lots-of-fields :a) nil]
                                       [:field (mt/id :lots-of-fields :b) nil]]}
                     :fields      (into [[:expression "c"]]
                                        (for [{:keys [id]} (t2/select [:model/Field :id]
                                                                      :table_id (mt/id :lots-of-fields)
                                                                      :id       [:not-in #{(mt/id :lots-of-fields :a)
                                                                                           (mt/id :lots-of-fields :b)}]
                                                                      {:order-by [[:name :asc]]})]
                                          [:field id nil]))})]
        (t2/with-call-count [call-count-fn]
          (mt/with-native-query-testing-context query
            (is (= 1
                   (-> (qp/process-query query) mt/rows ffirst))))
          (testing "# of app DB calls should not be some insane number"
            (is (< (call-count-fn) 20))))))))

(defmethod driver/database-supports? [::driver/driver ::expression-names-containing-slashes]
  [_driver _feature _database]
  true)

;;; Slashes documented as not allowed in BQ https://cloud.google.com/bigquery/docs/reference/standard-sql/lexical
(defmethod driver/database-supports? [:bigquery-cloud-sdk ::expression-names-containing-slashes]
  [_driver _feature _database]
  false)

(deftest ^:parallel expression-with-slashes
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions ::expression-names-containing-slashes)
    (testing "Make sure an expression with a / in its name works (#12305)"
      (is (= [[1 "Red Medicine"           4 10.0646 -165.374 3 4.0]
              [2 "Stout Burgers & Beers" 11 34.0996 -118.329 2 3.0]
              [3 "The Apple Pan"         11 34.0406 -118.428 2 3.0]]
             (mt/formatted-rows
              [int str int 4.0 4.0 int float]
              (mt/run-mbql-query venues
                {:expressions {:TEST/my-cool-new-field [:+ $price 1]}
                 :limit       3
                 :order-by    [[:asc $id]]})))))))

(deftest ^:parallel expression-using-aggregation-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (testing "Can we use aggregations from previous steps in expressions (#12762)"
      (is (= [["20th Century Cafe" 2 2 0]
              ["25°" 2 2 0]
              ["33 Taps" 2 2 0]]
             (sort-by first
                      (mt/formatted-rows
                       [str int int int]
                       (mt/run-mbql-query venues
                         {:source-query {:source-table (mt/id :venues)
                                         :aggregation  [[:min (mt/id :venues :price)]
                                                        [:max (mt/id :venues :price)]]
                                         :breakout     [[:field (mt/id :venues :name) nil]]
                                         :limit        3}
                          :expressions  {:price_range [:-
                                                       [:field "max" {:base-type :type/Number}]
                                                       [:field "min" {:base-type :type/Number}]]}}))))))))

(deftest ^:parallel expression-with-duplicate-column-name
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (testing "Can we use expression with same column name as table (#14267)"
      (mt/dataset test-data
        (let [query (mt/mbql-query products
                      {:expressions {:CATEGORY [:concat $category "2"]}
                       :breakout    [:expression :CATEGORY]
                       :aggregation [:count]
                       :order-by    [[:asc [:expression :CATEGORY]]]
                       :limit       1})]
          (mt/with-native-query-testing-context query
            (is (= [["Doohickey2" 42]]
                   (mt/formatted-rows
                    [str int]
                    (qp/process-query query))))))))))

(defmethod driver/database-supports? [::driver/driver ::fk-field-and-duplicate-names-test]
  [_driver _feature _database]
  true)

;;; TODO: Following _should probably_ work with Mongo, take a closer look working on #43901! -- lbrdnk
(defmethod driver/database-supports? [:mongo ::fk-field-and-duplicate-names-test]
  [_driver _feature _database]
  false)

(deftest ^:parallel fk-field-and-duplicate-names-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions :left-join ::fk-field-and-duplicate-names-test)
    (testing "Expressions with `fk->` fields and duplicate names should work correctly (#14854)"
      (mt/dataset test-data
        (let [results (mt/run-mbql-query orders
                        {:expressions {"CE" [:case
                                             [[[:> $discount 0] $created_at]]
                                             {:default $product_id->products.created_at}]}
                         :order-by    [[:asc $id]]
                         :limit       2})]
          (is (= ["ID" "User ID" "Product ID" "Subtotal" "Tax" "Total" "Discount" "Created At" "Quantity" "CE"]
                 (map :display_name (mt/cols results))))
          (is (= [[1 1  14  37.7  2.1  39.7 nil "2019-02-11T21:40:27Z" 2 "2017-12-31T14:41:56Z"]
                  [2 1 123 110.9  6.1 117.0 nil "2018-05-15T08:04:04Z"  3 "2017-11-16T13:53:14Z"]]
                 (mt/formatted-rows
                  [int int int 1.0 1.0
                   1.0 identity u.date/temporal-str->iso8601-str int u.date/temporal-str->iso8601-str]
                  results))))))))

(deftest ^:parallel string-operations-from-subquery
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions :regex)
    (testing "regex-match-first and replace work when evaluated against a subquery (#14873)"
      (mt/dataset test-data
        (let [r-word  "r_word"
              no-sp   "no_spaces"
              results (mt/run-mbql-query venues
                        {:expressions  {r-word [:regex-match-first $name "^R[^ ]+"]
                                        no-sp  [:replace $name " " ""]}
                         :source-query {:source-table $$venues}
                         :fields       [$name [:expression r-word] [:expression no-sp]]
                         :filter       [:= $id 1 95]
                         :order-by     [[:asc $id]]})]
          (is (= ["Name" r-word no-sp]
                 (map :display_name (mt/cols results))))
          (is (= [["Red Medicine" "Red" "RedMedicine"]
                  ["Rush Street" "Rush" "RushStreet"]]
                 (mt/formatted-rows
                  [str str str] results))))))))

(deftest ^:parallel expression-name-weird-characters-test
  (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
    (testing "An expression whose name contains weird characters works properly"
      (let [query (mt/mbql-query venues
                    {:expressions {"Refund Amount (?)" [:* $price -1]}
                     :limit       1
                     :order-by    [[:asc $id]]})]
        (mt/with-native-query-testing-context query
          (is (= [[1 "Red Medicine" 4 10.0646 -165.374 3 -3]]
                 (mt/formatted-rows
                  [int str int 4.0 4.0 int int]
                  (qp/process-query query)))))))))

(deftest ^:parallel join-table-on-itself-with-custom-column-test
  (testing "Should be able to join a source query against itself using an expression (#17770)"
    (mt/test-drivers (mt/normal-drivers-with-feature :nested-queries :expressions :left-join)
      (mt/dataset test-data
        (let [query (mt/mbql-query nil
                      {:source-query {:source-query {:source-table $$products
                                                     :aggregation  [[:count]]
                                                     :breakout     [$products.category]}
                                      :expressions  {:CC [:+ 1 1]}}
                       :joins        [{:source-query {:source-query {:source-table $$products
                                                                     :aggregation  [[:count]]
                                                                     :breakout     [$products.category]}
                                                      :expressions  {:CC [:+ 1 1]}}
                                       :alias        "Q1"
                                       :condition    [:=
                                                      [:field "CC" {:base-type :type/Integer}]
                                                      [:field "CC" {:base-type :type/Integer, :join-alias "Q1"}]]
                                       :fields       :all}]
                       :order-by     [[:asc $products.category]
                                      [:desc [:field "count" {:base-type :type/Integer}]]
                                      [:asc &Q1.products.category]]
                       :limit        1})]
          (mt/with-native-query-testing-context query
            ;; source.category, source.count, source.CC, Q1.category, Q1.count, Q1.CC
            (is (= [["Doohickey" 42 2 "Doohickey" 42 2]]
                   (mt/formatted-rows
                    [str int int str int int]
                    (qp/process-query query))))))))))

(deftest ^:parallel nested-expressions-with-existing-names-test
  (testing "Expressions with the same name as existing columns should work correctly in nested queries (#21131)"
    (mt/test-drivers (mt/normal-drivers-with-feature :nested-queries :expressions)
      (mt/dataset test-data
        (doseq [expression-name ["PRICE" "price"]]
          (testing (format "Expression name = %s" (pr-str expression-name))
            (let [query (mt/mbql-query products
                          {:source-query {:source-table $$products
                                          :expressions  {expression-name [:+ $price 2]}
                                          :fields       [$id $price [:expression expression-name]]
                                          :order-by     [[:asc $id]]
                                          :limit        2}})]
              (mt/with-native-query-testing-context query
                (is (= [[1 29.46 31.46] [2 70.08 72.08]]
                       (mt/formatted-rows
                        [int 2.0 2.0]
                        (qp/process-query query))))))))))))

(deftest ^:parallel question-mark-in-expression-name-test
  (testing "Custom column names containing a question mark should work correctly (#32543, #44915)"
    (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
      (let [query (mt/mbql-query venues
                    {:expressions {"Double Price?" [:+ $price 2]}
                     :fields      [[:expression "Double Price?"]]
                     :limit       3
                     :order-by    [[:asc $id]]})]
        (mt/with-native-query-testing-context query
          (is (= [[5] [4] [4]]
                 (mt/formatted-rows
                  [int] (qp/process-query query)))))))))

(deftest ^:parallel coercion-with-expression-test
  (testing "Coerced fields should be possible to use in aggregation even in the presence of an expression (#48721)"
    (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
      (mt/dataset sad-toucan-incidents
        (let [query (mt/mbql-query incidents
                      {:expressions {"double severity" [:* $severity 2]}
                       :aggregation [[:aggregation-options
                                      [:sum-where
                                       [:expression "double severity" {:base-type :type/Integer}]
                                       [:between $timestamp "2015-06-01" "2015-06-30"]]
                                      {:name "sum double severity June 2015"}]]})]
          (mt/with-native-query-testing-context query
            (is (= [[1020]]
                   (mt/formatted-rows [int] (qp/process-query query))))))))))

(deftest ^:parallel coercion-with-expression-test-2
  (testing "An expression in the breakout with a coerced column should work (#56886)"
    (mt/test-drivers (mt/normal-drivers-with-feature :expressions)
      (mt/dataset sad-toucan-incidents
        (let [query (mt/mbql-query incidents
                      {:expressions {"double severity" [:* $severity 2]}
                       :aggregation [[:count]]
                       :breakout    [$timestamp [:expression "double severity"]]
                       :limit       3})]
          (mt/with-native-query-testing-context query
            (is (= [["2015-06-01T00:00:00Z" 2 3]
                    ["2015-06-01T00:00:00Z" 6 1]
                    ["2015-06-01T00:00:00Z" 8 1]]
                   (mt/formatted-rows
                    [u.date/temporal-str->iso8601-str int int]
                    (qp/process-query query))))))))))

(deftest ^:parallel null-array-test
  (testing "a null array should be handled gracefully and return nil"
    (mt/test-drivers (mt/normal-drivers-with-feature :test/null-arrays)
      (is (= [[nil]]
             (-> (mt/native-query {:query (tx/native-null-array-query driver/*driver*)})
                 mt/process-query
                 mt/rows))))))
