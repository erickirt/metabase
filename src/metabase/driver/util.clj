(ns metabase.driver.util
  "Utility functions for common operations on drivers."
  #_{:clj-kondo/ignore [:metabase/modules]}
  (:require
   [clojure.core.memoize :as memoize]
   [clojure.set :as set]
   [clojure.string :as str]
   [metabase.app-db.core :as mdb]
   [metabase.auth-provider.core :as auth-provider]
   [metabase.config.core :as config]
   [metabase.driver :as driver]
   [metabase.driver.settings :as driver.settings]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.lib.metadata.protocols :as lib.metadata.protocols]
   [metabase.lib.schema.common :as lib.schema.common]
   [metabase.lib.schema.id :as lib.schema.id]
   [metabase.premium-features.core :as premium-features]
   [metabase.query-processor.error-type :as qp.error-type]
   [metabase.query-processor.store :as qp.store]
   [metabase.util :as u]
   [metabase.util.i18n :refer [deferred-tru trs]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.performance :as perf]
   [metabase.util.snake-hating-map :refer [snake-hating-map?]])
  (:import
   (java.io ByteArrayInputStream)
   (java.security KeyFactory KeyStore PrivateKey)
   (java.security.cert Certificate CertificateFactory X509Certificate)
   (java.security.spec PKCS8EncodedKeySpec)
   (javax.net SocketFactory)
   (javax.net.ssl KeyManagerFactory SSLContext TrustManagerFactory X509TrustManager)))

(set! *warn-on-reflection* true)

(def ^:private connection-error-messages
  "Generic error messages that drivers should return in their implementation
  of [[metabase.driver/humanize-connection-error-message]]."
  {:cannot-connect-check-host-and-port
   {:message (deferred-tru
              (str "Hmm, we couldn''t connect to the database."
                   " "
                   "Make sure your Host and Port settings are correct."))
    :errors  {:host (deferred-tru "check your host settings")
              :port (deferred-tru "check your port settings")}}

   :ssh-tunnel-auth-fail
   {:message (deferred-tru
              (str "We couldn''t connect to the SSH tunnel host."
                   " "
                   "Check the Username and Password."))
    :errors  {:tunnel-user (deferred-tru "check your username")
              :tunnel-pass (deferred-tru "check your password")}}

   :ssh-tunnel-connection-fail
   {:message (deferred-tru
              (str "We couldn''t connect to the SSH tunnel host."
                   " "
                   "Check the Host and Port."))
    :errors  {:tunnel-host (deferred-tru "check your host settings")
              :tunnel-port (deferred-tru "check your port settings")}}

   :database-name-incorrect
   {:message (deferred-tru "Looks like the Database name is incorrect.")
    :errors  {:dbname (deferred-tru "check your database name settings")}}

   :invalid-hostname
   {:message (deferred-tru
              (str "It looks like your Host is invalid."
                   " "
                   "Please double-check it and try again."))
    :errors  {:host (deferred-tru "check your host settings")}}

   :password-incorrect
   {:message (deferred-tru "Looks like your Password is incorrect.")
    :errors  {:password (deferred-tru "check your password")}}

   :password-required
   {:message (deferred-tru "Looks like you forgot to enter your Password.")
    :errors  {:password (deferred-tru "check your password")}}

   :username-incorrect
   {:message (deferred-tru "Looks like your Username is incorrect.")
    :errors  {:user (deferred-tru "check your username")}}

   :username-or-password-incorrect
   {:message (deferred-tru "Looks like the Username or Password is incorrect.")
    :errors  {:user     (deferred-tru "check your username")
              :password (deferred-tru "check your password")}}

   :certificate-not-trusted
   {:message (deferred-tru "Server certificate not trusted - did you specify the correct SSL certificate chain?")}

   :unsupported-ssl-key-type
   {:message (deferred-tru "Unsupported client SSL key type - are you using an RSA key?")}

   :invalid-key-format
   {:message (deferred-tru "Invalid client SSL key - did you select the correct file?")}

   :requires-ssl
   {:message (deferred-tru "Server appears to require SSL - please enable SSL below")
    :errors  {:ssl (deferred-tru "please enable SSL")}}

   :implicitly-relative-db-file-path
   {:message (deferred-tru "Implicitly relative file paths are not allowed.")
    :errors  {:db (deferred-tru "check your connection string")}}

   :db-file-not-found
   {:message (deferred-tru "Database cannot be found.")
    :errors  {:db (deferred-tru "check your connection string")}}})

(defn- tr-connection-error-messages [error-type-kw]
  (when-let [message (connection-error-messages error-type-kw)]
    (cond-> message
      (contains? message :message) (update :message str)
      (contains? message :errors)  (update :errors update-vals str))))

(defn- connection-error? [^Throwable throwable]
  (and (some? throwable)
       (or (instance? java.net.ConnectException throwable)
           (recur (.getCause throwable)))))

(defn can-connect-with-details?
  "Check whether we can connect to a database with `driver` and `details-map` and perform a basic query such as `SELECT
  1`. Specify optional param `throw-exceptions` if you want to handle any exceptions thrown yourself (e.g., so you
  can pass the exception message along to the user); otherwise defaults to returning `false` if a connection cannot be
  established.

     (can-connect-with-details? :postgres {:host \"localhost\", :port 5432, ...})"
  ^Boolean [driver details-map & [throw-exceptions]]
  {:pre [(keyword? driver) (map? details-map)]}
  (if throw-exceptions
    (try
      (u/with-timeout (driver.settings/db-connection-timeout-ms)
        (or (driver/can-connect? driver details-map)
            (throw (Exception. "Failed to connect to Database"))))
      ;; actually if we are going to `throw-exceptions` we'll rethrow the original but attempt to humanize the message
      ;; first
      (catch Throwable e
        (log/error e "Failed to connect to Database")
        (throw (if-let [humanized-message (some->> (.getMessage e)
                                                   (driver/humanize-connection-error-message driver))]
                 (let [error-data (cond
                                    (keyword? humanized-message)
                                    (tr-connection-error-messages humanized-message)

                                    (connection-error? e)
                                    (tr-connection-error-messages :cannot-connect-check-host-and-port)

                                    :else
                                    {:message humanized-message})]
                   (ex-info (str (:message error-data)) error-data e))
                 e))))
    (try
      (can-connect-with-details? driver details-map :throw-exceptions)
      (catch Throwable e
        (log/error e "Failed to connect to database")
        false))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                               Driver Resolution                                                |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private ^{:arglists '([db-id])} database->driver*
  (memoize/ttl
   (-> (mu/fn :- :keyword
         [db-id :- ::lib.schema.id/database]
         (qp.store/with-metadata-provider db-id
           (:engine (lib.metadata.protocols/database (qp.store/metadata-provider)))))
       (vary-meta assoc ::memoize/args-fn (fn [[db-id]]
                                            [(mdb/unique-identifier) db-id])))
   :ttl/threshold 1000))

(mu/defn database->driver :- :keyword
  "Look up the driver that should be used for a Database. Lightly cached.

  (This is cached for a second, so as to avoid repeated application DB calls if this function is called several times
  over the duration of a single API request or sync operation.)"
  [database-or-id :- [:or
                      {:error/message "Database or ID"}
                      [:map
                       [:engine [:or :keyword :string]]]
                      [:map
                       [:id ::lib.schema.id/database]]
                      ::lib.schema.id/database]]
  (if-let [driver (:engine database-or-id)]
    ;; ensure we get the driver as a keyword (sometimes it's a String)
    (keyword driver)
    (if (qp.store/initialized?)
      (:engine (lib.metadata/database (qp.store/metadata-provider)))
      (database->driver* (u/the-id database-or-id)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             Available Drivers Info                                             |
;;; +----------------------------------------------------------------------------------------------------------------+

(def supports?-timeout-ms
  "The maximum time in milliseconds that [[supports?]] should take to execute. This should be enough for a driver to
   query the database and check if it supports a feature under normal circumstances, but not so high that it delays
   critical metabase features that use this check."
  5000)

(def ^:dynamic *memoize-supports?*
  "If true, [[supports?]] is memoized for the application DB. Memoization is disabled in dev and test mode by default to avoid
   accidental coupling between tests."
  (not (or config/is-test? config/is-dev?)))

(defn- supports?* [driver feature database]
  (try
    (u/with-timeout supports?-timeout-ms
      (driver/database-supports? driver feature database))
    (catch Throwable e
      (log/error e (u/format-color 'red "Failed to check feature '%s' for database '%s'" (u/qualified-name feature) (:name database)))
      false)))

(def ^:private memoized-supports?*
  (memoize/memo
   (-> supports?*
       (vary-meta assoc ::memoize/args-fn
                  (fn [[driver feature database]]
                    [driver feature (mdb/unique-identifier) (:id database)
                     (if (snake-hating-map? database)
                       (:updated-at database)
                       (:updated_at database))])))))

(defn supports?
  "A defensive wrapper around [[database-supports?]]. It adds logging, caching, and error handling to avoid crashing the app
   if this method takes a long time to execute or throws an exception. This is useful because `supports?` is used in so many
   critical places in the app, and we don't want a single driver to crash the app if it throws an exception, or delay the user
   if it takes a long time to execute."
  [driver feature database]
  (let [f (if *memoize-supports?* memoized-supports?* supports?*)]
    (f driver feature database)))

(defn- features* [driver database]
  (set (for [feature driver/features
             :when (supports? driver feature database)]
         feature)))

(def ^:private memoized-features*
  (memoize/memo
   (-> features*
       (vary-meta assoc ::memoize/args-fn
                  (fn [[driver database]]
                    [driver (mdb/unique-identifier) (:id database)
                     (if (snake-hating-map? database)
                       (:updated-at database)
                       (:updated_at database))])))))

(defn features
  "Return a set of all features supported by `driver` with respect to `database`."
  [driver database]
  (let [f (if *memoize-supports?* memoized-features* features*)]
    (f driver database)))

(defn- supported-in-environment?
  "Returns true if a driver is supported in the the current metabase environment. As implemented this just disallows the
  sqlite driver on hosted metabase because hosted metabase does not support uploading a SQLite file for use."
  [driver]
  (or (not (premium-features/is-hosted?))
      (not= :sqlite (keyword driver))))

(defn available-drivers
  "Return a set of all currently available drivers."
  []
  (into #{} (filter #(and (driver/available? %) (supported-in-environment? %)))
        (descendants driver/hierarchy :metabase.driver/driver)))

(mu/defn semantic-version-gte :- :boolean
  "Returns true if xv is greater than or equal to yv according to semantic versioning.
   xv and yv are sequences of integers of the form `[major minor ...]`, where only
   major is obligatory.
   Examples:
   (semantic-version-gte [4 1] [4 1]) => true
   (semantic-version-gte [4 0 1] [4 1]) => false
   (semantic-version-gte [4 1] [4]) => true
   (semantic-version-gte [3 1] [4]) => false"
  [xv :- [:maybe [:sequential ::lib.schema.common/int-greater-than-or-equal-to-zero]]
   yv :- [:maybe [:sequential ::lib.schema.common/int-greater-than-or-equal-to-zero]]]
  (loop [xv (seq xv), yv (seq yv)]
    (or (nil? yv)
        (let [[x & xs] xv
              [y & ys] yv
              x (if (nil? x) 0 x)
              y (if (nil? y) 0 y)]
          (or (> x y)
              (and (>= x y) (recur xs ys)))))))

(defn- file-upload-props [{prop-name :name, visible-if :visible-if, disp-nm :display-name, :as conn-prop}]
  (if (premium-features/is-hosted?)
    [(-> (assoc conn-prop
                :name (str prop-name "-value")
                :type "textFile"
                :treat-before-posting "base64")
         (dissoc :secret-kind))]
    [(cond-> {:name (str prop-name "-options")
              :display-name disp-nm
              :type "select"
              :options [{:name (trs "Local file path")
                         :value "local"}
                        {:name (trs "Uploaded file path")
                         :value "uploaded"}]
              :default "local"}
       visible-if (assoc :visible-if visible-if))
     (-> {:name (str prop-name "-value")
          :type "textFile"
          :treat-before-posting "base64"
          :visible-if {(keyword (str prop-name "-options")) "uploaded"}}
         (dissoc :secret-kind))
     {:name (str prop-name "-path")
      :type "string"
      :display-name (trs "File path")
      :placeholder (:placeholder conn-prop)
      :visible-if {(keyword (str prop-name "-options")) "local"}}]))

(defn- ->str
  "Turns `x` into a String. If `x` a keyword, then `name` is used. Otherwise, `str` is called on it."
  [k]
  (if (keyword? k)
    (name k)
    (str k)))

(defn expand-secret-conn-prop
  "Expands a connection-property into multiple fields based on :secret-kind."
  [{prop-name :name, :as conn-prop}]
  (case (->str (:secret-kind conn-prop))
    "password"    [(-> conn-prop
                       (assoc :type "password")
                       (assoc :name (str prop-name "-value"))
                       (dissoc :secret-kind))]
    "keystore"    (file-upload-props conn-prop)
    ;; this may not necessarily be a keystore (could be a standalone PKCS-8 or PKCS-12 file)
    "binary-blob" (file-upload-props conn-prop)
    ;; PEM is a plaintext format
    ;; TODO: do we need to also allow a textarea type paste for this?  would require another special case
    "pem-cert"    (file-upload-props conn-prop)
    [conn-prop]))

(defn- resolve-info-conn-prop
  "Invokes the getter function on a info type connection property and adds it to the connection property map as its
  placeholder value. Returns nil if no placeholder value or getter is provided, or if the getter returns a non-string
  value or throws an exception."
  [{getter :getter, placeholder :placeholder, :as conn-prop}]
  (let [content (or placeholder
                    (try (getter)
                         (catch Throwable e
                           (log/errorf e "Error invoking getter for connection property %s" (:name conn-prop)))))]
    (when (string? content)
      (-> conn-prop
          (assoc :placeholder content)
          (dissoc :getter)))))

(defn- resolve-checked-section-conn-prop
  "Invokes the check function on a checked-section connection property and if truthy adds it to the connection property map."
  [{:keys [check] :as conn-prop}]
  (if (try (check)
           (catch Throwable e
             (log/errorf e "Error invoking getter for connection property %s" (:name conn-prop))))
    [(-> conn-prop
         (assoc :type "section")
         (dissoc :check))]
    []))

(defn- expand-schema-filters-prop [prop]
  (let [prop-name (:name prop)
        disp-name (or (:display-name prop) "")
        visible-if (:visible-if prop)
        placeholder (or (:placeholder prop) "E.x. public,auth*")
        type-prop-nm (str prop-name "-type")]
    [(merge {:name type-prop-nm
             :display-name disp-name
             :type "select"
             :options [{:name (trs "All")
                        :value "all"}
                       {:name (trs "Only these...")
                        :value "inclusion"}
                       {:name (trs "All except...")
                        :value "exclusion"}]
             :default "all"}
            {:visible-if visible-if})
     {:name (str prop-name "-patterns")
      :type "text"
      :placeholder placeholder
      :description (trs "Comma separated names of {0} that should appear in Metabase" (u/lower-case-en disp-name))
      :visible-if  {(keyword type-prop-nm) "inclusion"}
      :helper-text (trs "You can use patterns like \"auth*\" to match multiple {0}" (u/lower-case-en disp-name))
      :required true}
     {:name (str prop-name "-patterns")
      :type "text"
      :placeholder placeholder
      :description (trs "Comma separated names of {0} that should NOT appear in Metabase" (u/lower-case-en disp-name))
      :visible-if  {(keyword type-prop-nm) "exclusion"}
      :helper-text (trs "You can use patterns like \"auth*\" to match multiple {0}" (u/lower-case-en disp-name))
      :required true}]))

(defn find-schema-filters-prop
  "Finds the first property of type `:schema-filters` for the given `driver` connection properties. Returns `nil`
  if the driver has no property of that type."
  [driver]
  (first (filter (fn [conn-prop]
                   (= :schema-filters (keyword (:type conn-prop))))
                 (driver/connection-properties driver))))

(defn connection-props-server->client
  "Transforms `conn-props` for the given `driver` from their server side definition into a client side definition.

  This transforms :type :secret properties from the server side definition into other types for client
  display/editing. For example, a :secret-kind :keystore turns into a bunch of different properties, to encapsulate
  all the different options that might be available on the client side for populating the value.

  This also resolves the :getter function on :type :info properties and the :check function on :type :checked-sections,
   if one was provided."
  {:added "0.42.0"}
  [driver conn-props]
  (let [final-props
        (persistent!
         (reduce (fn [acc conn-prop]
                   ;; TODO: change this to expanded- and use that as the basis for all calcs below (not conn-prop)
                   (let [expanded-props (case (keyword (:type conn-prop))
                                          :secret
                                          (expand-secret-conn-prop conn-prop)

                                          :info
                                          (if-let [conn-prop' (resolve-info-conn-prop conn-prop)]
                                            [conn-prop']
                                            [])

                                          :checked-section
                                          (resolve-checked-section-conn-prop conn-prop)

                                          :schema-filters
                                          (expand-schema-filters-prop conn-prop)

                                          [conn-prop])]
                     (reduce conj! acc expanded-props)))
                 (transient [])
                 conn-props))
        props-by-name (reduce #(assoc %1 (:name %2) %2) {} final-props)]
    ;; now, traverse the visible-if-edges and update all visible-if entries with their full set of "transitive"
    ;; dependencies (if property x depends on y having a value, but y itself depends on z having a value, then x
    ;; should be hidden if y is)
    (mapv (fn [prop]
            (let [v-ifs*
                  (loop [props* [prop]
                         acc    {}]
                    (if (seq props*)
                      (let [all-visible-ifs  (reduce
                                              #(reduce-kv (fn [acc prop-name v]
                                                            (if (or (contains? props-by-name (->str prop-name))
                                                                    ;; If v is false then this depended on a removed :checked-section
                                                                    ;; and the dependency should be dropped.
                                                                    (not (false? v)))
                                                              (assoc acc prop-name v)
                                                              acc))
                                                          %1 (:visible-if %2))
                                              {} props*)
                            visible-keys     (keys all-visible-ifs)
                            transitive-props (perf/mapv (comp (partial get props-by-name) ->str) visible-keys)
                            next-acc         (into acc all-visible-ifs)]
                        (if-not (perf/some #(contains? acc %) visible-keys)
                          (recur transitive-props next-acc)
                          (let [cyclic-props (set/intersection (set visible-keys)
                                                               (set (keys acc)))]
                            (-> (trs "Cycle detected resolving dependent visible-if properties for driver {0}: {1}"
                                     driver cyclic-props)
                                (ex-info {:type               qp.error-type/driver
                                          :driver             driver
                                          :cyclic-visible-ifs cyclic-props})
                                throw))))
                      acc))]
              (cond-> prop
                (seq v-ifs*) (assoc :visible-if v-ifs*)
                (empty? v-ifs*) (dissoc :visible-if))))
          final-props)))

(def data-url-pattern
  "A regex to match data-URL-encoded files uploaded via the frontend"
  #"^data:[^;]+;base64,")

(def official-drivers
  "The set of all official drivers"
  #{"athena"
    "bigquery-cloud-sdk"
    "clickhouse"
    "databricks"
    "druid"
    "druid-jdbc"
    "h2"
    "mongo"
    "mysql"
    "oracle"
    "postgres"
    "presto-jdbc"
    "redshift"
    "snowflake"
    "sparksql"
    "sqlite"
    "sqlserver"
    "starburst"
    "vertica"})

(defn driver-source
  "Return the source type of the driver: official or community"
  [driver-name]
  (if (contains? official-drivers driver-name)
    "official"
    "community"))

(defn available-drivers-info
  "Return info about all currently available drivers, including their connection properties fields and supported
  features. The output of `driver/connection-properties` is passed through `connection-props-server->client` before
  being returned, to handle any transformation between the server side and client side representation."
  []
  (persistent!
   (reduce (fn [acc driver]
             (if-some [props (try
                               (->> (driver/connection-properties driver)
                                    (connection-props-server->client driver))
                               (catch Throwable e
                                 (log/errorf e "Unable to determine connection properties for driver %s" driver)))]
               ;; TODO - maybe we should rename `details-fields` -> `connection-properties` on the FE as well?
               (assoc! acc driver {:source {:type (driver-source (name driver))
                                            :contact (driver/contact-info driver)}
                                   :details-fields props
                                   :driver-name    (driver/display-name driver)
                                   :superseded-by  (driver/superseded-by driver)
                                   :extra-info     (driver/extra-info driver)})
               acc))
           (transient {}) (available-drivers))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             TLS Helpers                                                        |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- dn-for-cert
  [^X509Certificate cert]
  (.. cert getSubjectX500Principal getName))

(defn- key-type [key-string]
  (when-let [m (re-find #"^-----BEGIN (?:(\p{Alnum}+) )?PRIVATE KEY-----\n" key-string)]
    (m 1)))

(defn- parse-rsa-key
  "Parses an RSA private key from the PEM string `key-string`."
  ^PrivateKey [key-string]
  (let [algorithm (or (key-type key-string) "RSA")
        key-base64 (-> key-string
                       (str/replace #"^-----BEGIN (?:(\p{Alnum}+) )?PRIVATE KEY-----\n" "")
                       (str/replace #"\n-----END (?:(\p{Alnum}+) )?PRIVATE KEY-----\s*$" "")
                       (str/replace #"\s" ""))
        decoded (u/decode-base64-to-bytes key-base64)
        key-factory (KeyFactory/getInstance algorithm)] ; TODO support other algorithms
    (.generatePrivate key-factory (PKCS8EncodedKeySpec. decoded))))

(defn- parse-certificates
  "Parses a collection of X509 certificates from the string `cert-string`."
  [^String cert-string]
  (let [cert-factory (CertificateFactory/getInstance "X.509")
        cert-stream (ByteArrayInputStream. (.getBytes cert-string "UTF-8"))]
    (.generateCertificates cert-factory cert-stream)))

(defn generate-identity-store
  "Generates a `KeyStore` for the identity with key parsed from `key-string` protected by `password`
  and the certificate parsed from `cert-string` ."
  ^KeyStore [key-string password cert-string]
  (let [private-key (parse-rsa-key key-string)
        certificates (parse-certificates cert-string)]
    (doto (KeyStore/getInstance (KeyStore/getDefaultType))
      (.load nil nil)
      (.setKeyEntry (dn-for-cert (first certificates))
                    private-key
                    (char-array password)
                    (into-array Certificate certificates)))))

(defn generate-trust-store
  "Generates a `KeyStore` with built-in and custom certificates. The custom certificates are parsed from
  `cert-store`."
  ^KeyStore [cert-string]
  (let [certs (parse-certificates cert-string)
        keystore (doto (KeyStore/getInstance (KeyStore/getDefaultType))
                   (.load nil nil))
        ;; this TrustManagerFactory is used for cloning the default certs into the new TrustManagerFactory
        base-trust-manager-factory (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
                                     (.init ^KeyStore (cast KeyStore nil)))]
    (doseq [cert certs]
      (.setCertificateEntry keystore (dn-for-cert cert) cert))

    (doseq [^X509TrustManager trust-mgr (.getTrustManagers base-trust-manager-factory)]
      (when (instance? X509TrustManager trust-mgr)
        (doseq [issuer (.getAcceptedIssuers trust-mgr)]
          (.setCertificateEntry keystore (dn-for-cert issuer) issuer))))

    keystore))

(defn- key-managers [private-key password own-cert]
  (let [key-store (generate-identity-store private-key password own-cert)
        key-manager-factory (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))]
    (.init key-manager-factory key-store (char-array password))
    (.getKeyManagers key-manager-factory)))

(defn- trust-managers [trust-cert]
  (let [trust-store (generate-trust-store trust-cert)
        trust-manager-factory (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))]
    (.init trust-manager-factory trust-store)
    (.getTrustManagers trust-manager-factory)))

(defn ssl-context
  "Generates a `SSLContext` with the custom certificates added."
  ^javax.net.ssl.SSLContext [& {:keys [private-key own-cert trust-cert]}]
  (let [ssl-context (SSLContext/getInstance "TLS")]
    (.init ssl-context
           (when (and private-key own-cert) (key-managers private-key (str (random-uuid)) own-cert))
           (when trust-cert (trust-managers trust-cert))
           nil)
    ssl-context))

(defn ssl-socket-factory
  "Generates a `SocketFactory` with the custom certificates added."
  ^SocketFactory [& {:keys [_private-key _own-cert _trust-cert] :as args}]
  (.getSocketFactory (ssl-context args)))

(def default-sensitive-fields
  "Set of fields that should always be obfuscated in API responses, as they contain sensitive data."
  #{:password :pass :tunnel-pass :tunnel-private-key :tunnel-private-key-passphrase :access-token :refresh-token
    :service-account-json})

(defn sensitive-fields
  "Returns all sensitive fields that should be redacted in API responses for a given database. Calls get-sensitive-fields
  using the given database's driver, if that driver is valid and registered. Refer to get-sensitive-fields docstring
  for full details."
  [driver]
  (if-some [conn-prop-fn (get-method driver/connection-properties driver)]
    (let [all-fields      (conn-prop-fn driver)
          password-fields (filter #(contains? #{:password :secret} (keyword (get % :type))) all-fields)]
      (into default-sensitive-fields (map (comp keyword :name) password-fields)))
    default-sensitive-fields))

(defn fetch-and-incorporate-auth-provider-details
  "Incorporates auth-provider responses with db-details.

  If you have a database you need to pass the database-id as some providers will need to save the response (e.g. refresh-tokens)."
  ([driver db-details]
   (fetch-and-incorporate-auth-provider-details driver nil db-details))
  ([driver database-id {:keys [use-auth-provider auth-provider] :as db-details}]
   (if use-auth-provider
     (let [auth-provider (keyword auth-provider)]
       (driver/incorporate-auth-provider-details
        driver
        auth-provider
        (auth-provider/fetch-auth auth-provider database-id db-details)
        db-details))
     db-details)))
