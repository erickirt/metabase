(ns metabase.api.macros
  "NEW macro for defining REST API endpoints. See
  [Cam's tech design doc](https://www.notion.so/metabase/defendpoint-2-0-16169354c901806ca10cf45be6d91891) for
  motivation behind it.

  This new version makes it much easier to find and invoke the underlying functions for an endpoint;
  see [[find-route]] and [[find-route-fn]] for more information.

  See also the `comment` form at the bottom of this file for example REPL usages.

  TODO -- consider renaming this to `metabase.api.macros.defendpoint` or moving the 'meat and potatoes'
  of [[defendpoint]] there and having this be a simple Potemkin collect-and-export namespace... this namespace is
  already huge implementing just one macro and adding anything else here will make it out of control huge.

  On that note please don't add any unrelated macros to this namespace! Do cleanup first."
  (:require
   [clojure.core.specs.alpha]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clout.core :as clout]
   [compojure.response]
   [flatland.ordered.map :as ordered-map]
   [malli.core :as mc]
   [malli.error :as me]
   [malli.transform :as mtx]
   [medley.core :as m]
   [metabase.api.common.internal]
   [metabase.api.macros.defendpoint.open-api]
   [metabase.api.open-api :as open-api]
   [metabase.config.core :as config]
   [metabase.util :as u]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.describe :as umd]
   [metabase.util.malli.registry :as mr]
   [metabase.util.malli.schema :as ms]
   [ring.middleware.multipart-params]
   [ring.util.codec]))

;;;;
;;;; Malli schema
;;;;

(mr/def ::route
  [:map
   [:path string?]
   [:regexes {:optional true} [:map-of :keyword [:or
                                                 (ms/InstanceOfClass java.util.regex.Pattern)
                                                 ;; presumably a symbol naming a regex
                                                 symbol?
                                                 ;; presumably a form that evaluates to a regex
                                                 seq?]]]])

(mr/def ::param-type
  [:enum :route :query :body :request :respond :raise])

(mr/def ::params
  [:map-of
   ::param-type
   [:map
    [:binding some?]
    [:schema {:optional true} [:any {:description "Malli map schema for all the params of this type"}]]]])

(mr/def ::method
  [:enum :get :post :put :delete :patch])

(mr/def ::parsed-args
  [:map
   [:method          ::method]
   [:route           ::route]
   [:params          ::params]
   [:body            [:sequential any?]]
   [:response-schema {:optional true} [:maybe {:description "Malli schema for response. Note this is before we 'wrap if needed'"} any?]]
   [:docstr          {:optional true} [:maybe string?]]
   [:metadata        {:optional true} [:maybe {:description "Metadata map like you'd use with `defn`"} map?]]])

;;; TODO -- consider whether unique key really needs to include params + regexes or not. Maybe we should just disallow
;;; having two routes with the same method and param that only differ by regex patterns. It makes using this stuff more
;;; annoying
(mr/def ::unique-key
  "Unique indentifier for an api endpoint. `(:api/endpoints (meta a-namespace))` is a map of `::unique-key` => `::info`"
  [:tuple
   #_method ::method
   #_route  string?
   #_params [:map-of #_param keyword? #_regex-str string?]])

(mr/def ::core-fn
  "Schema for the underlying 'core' function generated by [[defendpoint]]. Has the form

    (f
     ([])
     ([route-params])
     ([route-params query-params])
     ([route-params query-params body-params])
     ([route-params query-params body-params request])"
  [:function
   [:=> [:cat]                     any?]
   [:=> [:cat any?]                any?]
   [:=> [:cat any? any?]           any?]
   [:=> [:cat any? any? any?]      any?]
   [:=> [:cat any? any? any? any?] any?]])

(mr/def ::request :map)

(mr/def ::respond-fn
  [:=> [:cat any?] any?])

(mr/def ::raise-fn
  [:=> [:cat (ms/InstanceOfClass Throwable)] any?])

(mr/def ::core-fn-async
  "Schema for the underlying 'core' function generated by [[defendpoint]] for async endpoints. Has the form

    (f
     ([respond raise])
     ([respond raise route-params])
     ([respond raise route-params query-params])
     ([respond raise route-params query-params body-params])
     ([respond raise route-params query-params body-params request])"
  [:function
   [:=> [:cat ::respond-fn ::raise-fn]                     any?]
   [:=> [:cat ::respond-fn ::raise-fn any?]                any?]
   [:=> [:cat ::respond-fn ::raise-fn any? any?]           any?]
   [:=> [:cat ::respond-fn ::raise-fn any? any? any?]      any?]
   [:=> [:cat ::respond-fn ::raise-fn any? any? any? any?] any?]])

(mr/def ::handler
  [:=> [:cat ::request ::respond-fn ::raise-fn] any?])

(mr/def ::middleware
  [:=> [:cat ::handler] ::handler])

(mr/def ::info
  "The info about an individual endpoint that gets stored in the namespace metadata."
  [:map
   [:core-fn ::core-fn]
   [:handler ::handler]
   [:form    ::parsed-args]])

;;;;
;;;; spec (only used for macroexpansion)
;;;;

(s/def ::defendpoint.route.regex
  (some-fn symbol?
           string?
           #(instance? java.util.regex.Pattern %)
           ;; I guess it would be possible to have something like (re-pattern whatever...) here
           seq?))

(s/def ::defendpoint.route.key-regex-pair
  (s/cat :key   keyword?
         :regex ::defendpoint.route.regex))

(s/def ::defendpoint.route
  ;; TODO -- make the string stricter
  (s/or
   :path   string?
   ;; a vector like ["whatever/:export-format" :export-format some-regex]
   :vector (s/and
            vector?
            (s/spec (s/cat :path    string?
                           :regexes (s/+ ::defendpoint.route.key-regex-pair))))))

(mu/defn- infer-route-param-regex :- [:maybe (ms/InstanceOfClass java.util.regex.Pattern)]
  [route-param :- :keyword
   route-params-schema]
  (when (and route-params-schema
             (= (mc/type route-params-schema) :map))
    (some (fn [[k _options v-schema]]
            (when (= k route-param)
              (or
               (:api/regex (mc/properties v-schema))
               (second (metabase.api.common.internal/->matching-regex v-schema)))))
          (mc/children route-params-schema))))

(mu/defn- inferred-route-regexes :- [:maybe [:map-of :keyword (ms/InstanceOfClass java.util.regex.Pattern)]]
  "Auto-infer regexes for the route based on the route args schema. These are either defined by the `:api/regex`
  property in the schema itself (see [[metabase.api.macros-test/RouteParams]] for an example of this), or if one is not
  specified, in [[metabase.api.common.internal/->matching-regex]]."
  [route :- :string
   args  :- :map]
  (when-let [ks (not-empty (metabase.api.common.internal/route-arg-keywords route))]
    (let [route-params-schema (some-> (get-in args [:params :route :schema])
                                      #_:clj-kondo/ignore
                                      eval
                                      mr/resolve-schema
                                      mc/schema)]
      (into
       {}
       (keep (fn [k]
               (or (when-let [regex (infer-route-param-regex k route-params-schema)]
                     [k regex])
                   ;; in dev (REPL usage) warn if we didn't infer a regex for a route param so people can consider
                   ;; adding one.
                   (when config/is-dev?
                     (log/warnf (str "Warning: no regex explicitly specified or inferred for %s %s %s route param %s,"
                                     " add an :api/regex key to its schema or explicitly specify route regexes.")
                                (ns-name *ns*)
                                (:method args)
                                (pr-str route)
                                k)))))
       ks))))

(mu/defn- parse-route :- ::route
  [[route-type route] :- [:tuple [:enum :path :vector] :any]
   args               :- :map]
  (case route-type
    :path   (merge
             {:path route}
             (when-let [regexes (not-empty (inferred-route-regexes route args))]
               {:regexes regexes}))
    :vector (update route :regexes #(into {} (map (juxt :key :regex)) %))))

(s/def ::defendpoint.schema-specifier
  (s/?
   (s/cat
    :horn   #{:-}
    :schema any?)))

(s/def ::defendpoint.params.param
  (s/cat
   :binding (s/nonconforming :clojure.core.specs.alpha/binding-form)
   :schema  ::defendpoint.schema-specifier))

(s/def ::defendpoint.params
  (s/and
   vector?
   (s/spec
    (s/cat
     :route         (s/? ::defendpoint.params.param)
     :query         (s/? ::defendpoint.params.param)
     :body          (s/? ::defendpoint.params.param)
     :request       (s/? ::defendpoint.params.param)
     :respond-raise (s/? (s/cat :respond symbol?
                                :raise   symbol?))))))

(mu/defn- parse-params :- ::params
  [params]
  (letfn [(parse-schema [param]
            (cond-> param
              (:schema param) (update :schema :schema)))]
    (merge
     (reduce
      (fn [params k]
        (cond-> params
          (k params) (update k parse-schema)))
      (dissoc params :respond-raise)
      [:route :query :body :request])
     (when-let [{:keys [respond raise]} (:respond-raise params)]
       {:respond {:binding respond}
        :raise   {:binding raise}}))))

(s/def ::defendpoint
  (s/cat
   :method          #{:get :post :put :delete :patch}
   :route           ::defendpoint.route
   :response-schema ::defendpoint.schema-specifier
   :docstr          (s/? string?)
   :metadata        (s/? map?)
   :params          ::defendpoint.params
   :body            (s/* any?)))

(mu/defn- parse-args :- ::parsed-args
  [args :- [:sequential any?]]
  (let [conformed (s/conform ::defendpoint args)]
    (when (= conformed :clojure.spec.alpha/invalid)
      (throw (ex-info (format "Unable to parse defendpoint args: %s" (s/explain-str ::defendpoint args))
                      {:args  args
                       :error (s/explain-data ::defendpoint args)})))
    (as-> conformed conformed
      (update conformed :params parse-params)
      (update conformed :route parse-route conformed)
      (cond-> conformed
        (:response-schema conformed) (update :response-schema :schema)))))

;;;;
;;;; Macro etc.
;;;;

(mu/defn- unique-key-form
  "Form generated by [[defendpoint]] to return a unique key for a given endpoint (matches the [[::unique-key]] schema),
  based on method + route + param regexes."
  {:style/indent [:form]}
  [{:keys [method route]} :- ::parsed-args]
  [method
   (:path route)
   ;; deduplicate two endpoints with the same method and route but different regexes, e.g. one `:get ":/id"` with an
   ;; integer ID and one with a string ID. Since two identical regex patterns are not considered `=` convert them to
   ;; strings.
   (update-vals (:regexes route) (fn [regex-form]
                                   `(str ~regex-form)))])

(mu/defn- unique-fn-name
  "Generate a unique name for the endpoint function based on parsed args."
  [{:keys [method route]} :- ::parsed-args]
  (symbol
   (-> (str (name method) "-" (:path route) "--thunk")
       (str/replace #"/" "-")
       (str/replace #" " "-")
       (str/replace #":" ""))))

(def ^:private decode-transformer
  (mtx/transformer
   (mtx/string-transformer)
   (mtx/json-transformer)
   (mtx/default-value-transformer)
   {:name :api}
   {:name :normalize}))

(def ^:private encode-transformer
  (mtx/transformer
   (mtx/default-value-transformer)
   {:name :api}))

(defn- decoder [schema]
  (mr/cached ::decoder schema #(mc/decoder schema decode-transformer)))

(defn- encoder [schema]
  (mr/cached ::encoder schema #(mc/encoder schema encode-transformer)))

(def ^:dynamic *enable-response-validation*
  "Whether to validate responses against Malli schemas in [[defendpoint]]... normally enabled for dev/test and disabled
  for prod for performance purposes. You can change this binding if you want to disable it for weird test reasons or
  whatever.

  Not that encoding is still applied regardless of this value, i.e. even in prod."
  (not config/is-prod?))

(mu/defn validate-and-encode-response :- any?
  "Impl for [[endpoint-core-fn]]; validate the endpoint response against `schema` "
  [schema   :- some?
   response :- any?]
  (when *enable-response-validation*
    (when-not (mr/validate schema response)
      (throw (ex-info "Invalid response" ; TODO -- better error message?
                      {:status-code 400
                       :error       (-> schema
                                        (mr/explain response)
                                        me/with-spell-checking
                                        (me/humanize {:wrap mu/humanize-include-value}))}))))
  ((encoder schema) response))

(mu/defn- params-binding :- some?
  [args        :- ::parsed-args
   params-type :- ::param-type]
  (get-in args [:params params-type :binding] '_))

(defn- invalid-params-specific-errors [explanation]
  (-> explanation
      me/with-spell-checking
      (me/humanize {:wrap mu/humanize-include-value})))

(defn- invalid-params-errors [schema explanation specific-errors]
  (or (when (and (map? specific-errors)
                 (= (mc/type schema) :map))
        (into {}
              (let [specific-error-keys (set (keys specific-errors))]
                (keep (fn [child]
                        (when (contains? specific-error-keys (first child))
                          [(first child) (umd/describe (last child))]))))
              (mc/children schema)))
      (me/humanize explanation {:wrap #(umd/describe (:schema %))})))

(mu/defn decode-and-validate-params
  "Impl for [[defendpoint]]."
  [params-type :- ::param-type
   schema      :- some?
   params]
  (let [params  (or params {})
        decoded ((decoder schema) params)]
    (when-not (mr/validate schema decoded)
      (throw (ex-info (format "Invalid %s" (case params-type
                                             :route "route parameters"
                                             :query "query parameters"
                                             :body  "body"))
                      (let [explanation     (mr/explain schema decoded)
                            specific-errors (invalid-params-specific-errors explanation)
                            errors          (invalid-params-errors schema explanation specific-errors)]
                        {:status-code     400
                         #_:api/debug     #_{:params-type params-type
                                             :schema      (mc/form schema)
                                             :params      params
                                             :decoded     decoded}
                         :specific-errors specific-errors
                         :errors          errors}))))
    decoded))

(mu/defn- decode-and-validate-params-form
  [args        :- ::parsed-args
   params-type :- ::param-type
   form]
  (if-let [schema (get-in args [:params params-type :schema])]
    `(decode-and-validate-params ~params-type ~schema ~form)
    form))

(defn- render-response [response request]
  (-> response
      metabase.api.common.internal/wrap-response-if-needed
      (compojure.response/render request)))

(mu/defn -core-fn :- ::core-fn
  "Helper for [[endpoint-core-fn]]. Takes a 4-arity function generated by [[endpoint-core-fn]] and adds 0-3
  arities, and adds a call to [[render-response]]."
  [f :- [:=> [:cat any? any? any? any?] any?]]
  (fn core-fn
    ([]
     (f nil nil nil nil))
    ([route-params]
     (f route-params nil nil nil))
    ([route-params query-params]
     (f route-params query-params nil nil))
    ([route-params query-params body-params]
     (f route-params query-params body-params nil))
    ([route-params query-params body-params request]
     (-> (f route-params query-params body-params request)
         (render-response request)))))

(mu/defn -core-fn-async :- ::core-fn-async
  "Helper for [[endpoint-core-fn]]. Takes a 3-arity function generated by [[endpoint-core-fn]] and adds zero, one,
  and two arities, and adds a call to [[metabase.api.common.internal/wrap-response-if-needed]]."
  [f :- [:=> [:cat ::respond-fn ::raise-fn any? any? any? any?] any?]]
  (fn core-fn
    ([respond raise]
     (f respond raise nil nil nil nil))
    ([respond raise route-params]
     (f respond raise route-params nil nil nil))
    ([respond raise route-params query-params]
     (f respond raise route-params query-params nil nil))
    ([respond raise route-params query-params body-params]
     (f respond raise route-params query-params body-params nil))
    ([respond raise route-params query-params body-params request]
     (let [respond' (fn [response]
                      (-> response
                          (render-response request)
                          respond))]
       (f respond' raise route-params query-params body-params request)))))

(defmacro endpoint-core-fn*
  "Impl for [[endpoint-core-fn]]"
  {:style/indent [:form]}
  [{:keys [body response-schema], :as args}]
  (let [async?       (get-in args [:params :respond])
        route-params (gensym "route-params-")
        query-params (gensym "query-params-")
        body-params  (gensym "body-params-")
        request      (gensym "request-")]
    `(~(if async? `-core-fn-async `-core-fn)
      (fn
        [~@(when async?
             [(get-in args [:params :respond :binding])
              (get-in args [:params :raise :binding])])
         ~route-params ~query-params ~body-params ~request]
        (let [~(params-binding args :route)   ~(decode-and-validate-params-form args :route   route-params)
              ~(params-binding args :query)   ~(decode-and-validate-params-form args :query   query-params)
              ~(params-binding args :body)    ~(decode-and-validate-params-form args :body    body-params)
              ~(params-binding args :request) ~(decode-and-validate-params-form args :request request)]
          ~@(if response-schema
              `[(validate-and-encode-response ~response-schema (do ~@body))]
              body))))))

(defn validate-schema
  "Impl for [[endpoint-core-fn]]: validate the schemas used for validation at evaluation time, so we can get instant
  feedback if they're bad as opposed to waiting for someone to actually use the endpoint."
  [schema-type schema]
  (try
    (mc/schema schema)
    (catch Throwable e#
      (throw (ex-info (format "Invalid %s schema: %s\n\n%s"
                              (name schema-type)
                              (ex-message e#)
                              (u/pprint-to-str schema))
                      {:schema schema})))))

(defmacro endpoint-core-fn-with-optimized-schemas
  "Helper macro for [[endpoint-core-fn]]. This is not strictly necessary, but improves performance somewhat by
  rewriting the code so parameter and response schemas are defined one time outside the underlying endpoints functions
  and lexically closed over rather than being defined inline and re-evaluated on each API request. E.g. instead of
  defining a core function like

    (-core-fn
     (fn [_route-params _query-params body-params-1234 _request]
       (let [body (decode-and-validate-params :body
                                              [:map
                                               [:name ms/NonBlankString]
                                               [:default {:optional true} [:maybe :boolean]]]
                                              body-params-1234)]
         ...)))

  we're defining something closer to

    (let [body-schema-5678 [:map
                            [:name ms/NonBlankString]
                            [:default {:optional true} [:maybe :boolean]]]]
      (-core-fn
       (fn [_route-params _query-params body-params-1234 _request]
         (let [body (decode-and-validate-params :body body-schema-5678 body-params-1234)]
           ...))))

  Note that the schema functions are generated only once and reused for better performance.
  See: [[mr/validator]], [[mr/expaliner]], [[decoder]], and [[encoder]].

  This is a drop-in wrapper for a form like

    `(endpoint-core-fn* ~parsed-args)

  e.g. you can replace it with

    `(endpoint-core-fn-with-optimized-schemas
       (endpoint-core-fn* ~parsed-args))"
  [[f parsed]]
  (let [route-params-schema (when (get-in parsed [:params :route :schema])
                              (gensym "route-params-schema-"))
        query-params-schema (when (get-in parsed [:params :query :schema])
                              (gensym "query-params-schema-"))
        body-params-schema  (when (get-in parsed [:params :body :schema])
                              (gensym "body-schema-"))
        request-schema      (when (get-in parsed [:params :request :schema])
                              (gensym "request-schema-"))
        response-schema     (when (:response-schema parsed)
                              (gensym "response-schema-"))
        parsed'             (cond-> parsed
                              route-params-schema (assoc-in [:params :route :schema]   route-params-schema)
                              query-params-schema (assoc-in [:params :query :schema]   query-params-schema)
                              body-params-schema  (assoc-in [:params :body :schema]    body-params-schema)
                              request-schema      (assoc-in [:params :request :schema] request-schema)
                              response-schema     (assoc :response-schema response-schema))]
    `(let [~@(when route-params-schema
               [route-params-schema (get-in parsed [:params :route :schema])])
           ~@(when query-params-schema
               [query-params-schema (get-in parsed [:params :query :schema])])
           ~@(when body-params-schema
               [body-params-schema (get-in parsed [:params :body :schema])])
           ~@(when request-schema
               [request-schema (get-in parsed [:params :request :schema])])
           ~@(when response-schema
               [response-schema (:response-schema parsed)])]
       ;; make sure all the schemas are actually valid at eval time, this will save us a lot of drama if we know right
       ;; away as opposed to not finding out until we actually use the endpoints
       ~@(when route-params-schema
           `[(validate-schema :route ~route-params-schema)])
       ~@(when query-params-schema
           `[(validate-schema :query ~query-params-schema)])
       ~@(when body-params-schema
           `[(validate-schema :body ~body-params-schema)])
       ~@(when request-schema
           `[(validate-schema :request ~request-schema)])
       ~@(when response-schema
           `[(validate-schema :response ~response-schema)])
       (~f ~parsed'))))

(defmacro endpoint-core-fn
  "Impl for [[defendpoint]]. Generate the core function wrapper for an endpoint. You can get this to play with from the
  REPL or tests with [[find-route-fn]]. Functions match the schema for [[::core-fn]]."
  {:style/indent [:form]}
  [parsed-args]
  `(endpoint-core-fn-with-optimized-schemas
    (endpoint-core-fn* ~parsed-args)))

(mu/defn- params :- [:maybe [:map-of keyword? any?]]
  "Fetch `:route` or `:query` parameters from a `request`."
  [request     :- ::request
   params-type :- [:enum :route :query]]
  (case params-type
    :route (:route-params request)
    :query (some-> (:query-params request) (update-keys keyword))))

(mu/defn- request-body
  [request :- :map]
  (or (some-> (not-empty (:form-params request)) (update-keys keyword))
      (when-let [body (:body request)]
        (when-not (instance? org.eclipse.jetty.ee9.nested.HttpInput body)
          body))))

(mu/defn- middleware-forms
  "Middleware to apply to base handler. Currently the only option is middleware for handling multipart requests, applied
  if the handler metadata contains

    {:multipart true}"
  [{:keys [metadata], :as _args} :- ::parsed-args]
  (when (:multipart metadata)
    '[ring.middleware.multipart-params/wrap-multipart-params]))

(mu/defn- apply-middleware :- ::handler
  [handler    :- ::handler
   middleware :- [:maybe [:sequential ::middleware]]]
  (reduce
   (fn [handler middleware]
     (middleware handler))
   handler
   middleware))

(mu/defn endpoint-handler* :- ::handler
  "Generate the a Ring handler (excluding the Clout/Compojure method/route matching stuff) for parsed [[defendpoint]]
  args."
  {:style/indent [:form]}
  [middleware       :- [:maybe [:sequential ::middleware]]
   core-fn          :- ::core-fn
   {:keys [async?]} :- [:map
                        [:async? :boolean]]]
  (let [handler (if async?
                  (fn async-handler [request respond raise]
                    (let [route-params (params request :route)
                          query-params (params request :query)
                          body-params  (request-body request)]
                      (core-fn respond raise route-params query-params body-params request)))
                  (fn handler [request respond raise]
                    (try
                      (let [route-params (params request :route)
                            query-params (params request :query)
                            body-params  (request-body request)]
                        (respond (core-fn route-params query-params body-params request)))
                      (catch Throwable e
                        (raise e)))))]
    ;; apply all middleware to the base handler
    (apply-middleware handler middleware)))

(defmacro endpoint-handler
  "Generate the a Ring handler (excluding the Clout/Compojure method/route matching stuff) for parsed [[defendpoint]]
  args."
  {:style/indent [:form]}
  [parsed-args core-fn]
  (let [async? (boolean (get-in parsed-args [:params :respond]))]
    `(let [middleware# ~(middleware-forms parsed-args)]
       (endpoint-handler* middleware# ~core-fn {:async? ~async?}))))

(mr/def ::ns-endpoints [:map-of ::unique-key ::info])

(mr/def ::handler-map
  [:map-of ::method [:sequential [:tuple (ms/InstanceOfClass clout.core.CompiledRoute) ::handler]]])

(mu/defn- ns-handler-map :- ::handler-map
  "Build a map of method => [[clout-route handler]+] used to power the combined ns handler built
  by [[build-ns-handler]]."
  [endpoints :- ::ns-endpoints]
  (->> endpoints
       vals
       (group-by #(get-in % [:form :method]))
       (m/map-vals (fn [routes]
                     (mapv (fn [route]
                             [(clout/route-compile (get-in route [:form :route :path])
                                                   (get-in route [:form :route :regexes] {}))
                              (:handler route)])
                           routes)))))

(defn- decode-route-params [route-params]
  (update-vals route-params ring.util.codec/url-decode))

(mu/defn- find-matching-handler :- [:maybe [:tuple ::request ::handler]]
  "Find the appropriate handler from `handler-map` to handle `request`. Returns a tuple of

    [request' handler]

  (Request is updated to include parsed Clout parameters.)"
  [handler-map :- ::handler-map
   request      :- ::request]
  (let [request-method (:request-method request)
        handlers       (get handler-map request-method)
        path           (:compojure/path request)
        request        (cond-> request
                         path (assoc :path-info path))]
    ;; TODO -- we could probably make this a little faster by unrolling this loop
    (some (fn [[route handler]]
            (when-let [route-params (clout/route-matches route request)]
              [(assoc request :route-params (decode-route-params route-params))
               handler]))
          handlers)))

(mu/defn- build-ns-handler :- ::handler
  "Build a combined Ring handler for all `endpoints` that routes requests to the matching handler (if any)."
  [endpoints :- ::ns-endpoints]
  (let [handler-map (ns-handler-map endpoints)]
    (open-api/handler-with-open-api-spec
     (fn ns-handler* [request respond raise]
       (if-let [[request* handler] (find-matching-handler handler-map request)]
         (handler request* respond raise)
         (respond nil)))
     (fn [prefix]
       (metabase.api.macros.defendpoint.open-api/open-api-spec endpoints prefix)))))

(mu/defn update-ns-endpoints!
  "Update the information about and handler stored in namespace metadata for the endpoints defined by [[defendpoint]]."
  [nmspace
   k    :- ::unique-key
   info :- ::info]
  ;; we don't want to modify ns metadata during compilation, this will cause it to contain stuff like
  ;;
  ;;    #function[metabase.api.macros/fn--61462/endpoint-handler61461--61463/f--61464]
  ;;
  ;; that can't get loaded again when you use the uberjar, we'll just recreate this stuff on namespace load.
  (when-not *compile-files*
    (alter-meta!
     (the-ns nmspace)
     (fn [metadata]
       (letfn [(update-api-endpoints [api-endpoints]
                 ;; use an ordered map to preserve the order the endpoints were defined
                 (assoc (or api-endpoints (ordered-map/ordered-map)) k info))
               (update-info [metadata]
                 (update metadata :api/endpoints update-api-endpoints))
               (rebuild-handler [metadata]
                 (assoc metadata :api/handler (build-ns-handler (:api/endpoints metadata))))]
         (-> metadata update-info rebuild-handler))))))

(defn- quote-parsed-args
  "Quote the appropriate parts of the parsed [[defendpoint]] args (body and param bindings) so they can be emitted in
  the [[update-ns-endpoints!]] function call generated by [[defendpoint]]."
  [parsed]
  (letfn [(quote-form [form]
            (list 'quote form))
          (quote-param-bindings-of-type [params param-type]
            (cond-> params
              (get-in params [param-type :binding]) (update-in [param-type :binding] quote-form)))
          (quote-param-bindings [params]
            (reduce
             quote-param-bindings-of-type
             params
             [:route :query :body :request :respond :raise]))]
    (-> parsed
        (update :body quote-form)
        (update :params quote-param-bindings))))

(defmacro defendpoint
  "NEW macro for defining REST API endpoints. See
  [Cam's tech design doc](https://www.notion.so/metabase/defendpoint-2-0-16169354c901806ca10cf45be6d91891) for
  motivation behind it.

  REPL Tip: use [[call-core-fn]] to call the core-fn directly."
  {:added "0.53.0"}
  [& args]
  (let [parsed (parse-args args)]
    `(let [core-fn#  (endpoint-core-fn ~parsed)
           handler#  (endpoint-handler ~parsed core-fn#)
           info#     {:core-fn core-fn#
                      :handler handler#
                      :form    ~(quote-parsed-args parsed)}]
       (update-ns-endpoints! *ns* ~(unique-key-form parsed) info#)
       (fn ~(unique-fn-name parsed) [] info#))))

(s/fdef defendpoint
  :args ::defendpoint
  :ret  any?)

(mu/defn ns-handler :- ::handler
  "Return the combined Ring handler for all endpoints defined in a namespace.

  You can use this to define `routes` for a namespace e.g.

    (def ^{:arglists '([request respond raise]) routes
      \"/api/search/ routes\"
      (api.macros/ns-handler))

  Optional first arg can be used to specify the namespace; defaults to [[*ns*]]. Any additional args are Ring middleware
  that wraps the handler, e.g.

    (def routes
      \"/api/search/ routes\"
      (api.macros/ns-handler *ns* middleware-1 middleware-2))"
  ([]
   (ns-handler *ns*))

  ([nmspace]
   (let [nmspace         (the-ns nmspace)
         resolve-handler (fn []
                           (-> nmspace meta :api/handler))]
     (cond
       (not (resolve-handler))
       (fn pass-thru-handler [_request respond _raise]
         (respond nil))

       ;; for dev, fetch the handler from the metadata on every request so we get nice live reloading if the endpoints
       ;; in a namespace change.
       config/is-dev?
       (open-api/handler-with-open-api-spec
        (fn dev-handler [request respond raise]
          ((resolve-handler) request respond raise))
        (fn [prefix]
          (open-api/open-api-spec (resolve-handler) prefix)))

       ;; For prod, fetching the handler on each request gives us nothing since it shouldn't change; fetch it once and
       ;; if it's not defined just use the [[pass-thru-handler]] above instead.
       :else
       (resolve-handler))))

  ([nmspace & middleware :- [:sequential {:min 1} ::middleware]]
   (apply-middleware
    (ns-handler nmspace)
    middleware)))

(extend-protocol open-api/OpenAPISpec
  clojure.lang.Namespace
  (open-api-spec [a-namespace prefix]
    (open-api/open-api-spec (ns-handler a-namespace) prefix)))

;;;
;;; REPL/test conveniences
;;;

(defn reset-ns-routes!
  "For REPL convenience: remove all endpoints associated with a namespace from its metadata. This is useful if you
  delete/rename endpoints in a namespace and want the latest changes to get picced up."
  ([]
   (reset-ns-routes! *ns*))
  ([nmspace]
   (alter-meta! (the-ns nmspace) dissoc :api/endpoints)))

(mu/defn ns-routes :- ::ns-endpoints
  "Get the REST API endpoint handlers and forms in a namespace from its metadata."
  ([]
   (ns-routes *ns*))
  ([nmspace]
   (-> nmspace the-ns meta :api/endpoints))
  ([nmspace
    method :- ::method]
   (let [routes (ns-routes nmspace)]
     (into (empty routes)
           (filter (fn [[k _info]]
                     (= (first k) method)))
           routes)))
  ([nmspace
    method :- ::method
    route  :- string?]
   (let [routes (ns-routes nmspace method)]
     (into (empty routes)
           (filter (fn [[k _info]]
                     (= (second k) route)))
           routes))))

(mu/defn find-route :- [:schema
                        {:description (format "Tip: you can use %s to list all the defendpoint 2 routes in a namespace" `ns-routes)}
                        ::info]
  "Find the info for a specific route."
  [nmspace
   method :- ::method
   route  :- string?]
  (first (vals (ns-routes nmspace method route))))

(mu/defn find-route-fn :- ::core-fn
  "Find the info for a specific route."
  [nmspace
   method :- ::method
   route  :- string?]
  (:core-fn (find-route nmspace method route)))

(defn call-core-fn
  "Call this on the return value of [[defendpoint]] to get the core function for the endpoint.

  You can call that from the repl on the args [[-core-fn]] expects as in [[metabase.api.open-api-test/get-core-fn!-test]]."
  [defendpoint-return & [route-params query-params body-params request]]
  ((:core-fn (defendpoint-return)) route-params query-params body-params request))

;;;;
;;;; Example usages
;;;;

#_{:clj-kondo/ignore [:aliased-namespace-symbol]}
(comment
  (metabase.api.macros/ns-routes 'metabase.timeline.api.timeline)
  (metabase.api.macros/ns-routes 'metabase.timeline.api.timeline :get)
  (metabase.api.macros/ns-routes 'metabase.timeline.api.timeline :get "/:id")

  (reset-ns-routes! 'metabase.timeline.api.timeline)

  (metabase.api.open-api/open-api-spec (metabase.api.macros/ns-handler 'metabase.timeline.api.timeline) "/api/timeline/")
  (metabase.api.open-api/open-api-spec (the-ns 'metabase.timeline.api.timeline) "/api/timeline/")

  (find-route 'metabase.timeline.api.timeline :get "/")
  (find-route 'metabase.timeline.api.timeline :get "/:id")
  (find-route-fn 'metabase.timeline.api.timeline :get "/:id"))

;;; PLEASE DON'T ADD ANY MORE CODE AFTER THE EXAMPLE USAGES ABOVE, GO ADD IT SOMEWHERE ELSE. PLEASE DON'T ADD ANYTHING
;;; UNRELATED TO DEFENDPOINT, THIS NAMESPACE IS ALREADY HUGE AND IF WE START PUTTING OTHER STUFF IN IT IT WILL BE
;;; IMPOSSIBLE TO UNDERSTAND.
