(ns com.walmartlabs.lacinia.pedestal-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [clojure.java.io :as io]
    [com.walmartlabs.lacinia.util :as util]
    [com.walmartlabs.lacinia.schema :as schema]
    [com.walmartlabs.lacinia.resolve :refer [resolve-as]]
    [com.walmartlabs.lacinia.pedestal :as lp]
    [io.pedestal.http :as http]
    [clj-http.client :as client]
    [cheshire.core :as cheshire]
    [clojure.edn :as edn]))

(defn ^:private resolve-echo
  [context args _]
  (let [{:keys [value error]} args
        error-map (when error
                    {:message "Forced error."
                     :status error})
        resolved-value {:value value
                        :method (get-in context [:request :request-method])}]
    (resolve-as resolved-value error-map)))

(use-fixtures :once
  (fn [f]
    (let [schema (-> (io/resource "sample-schema.edn")
                     slurp
                     edn/read-string
                     (util/attach-resolvers {:resolve-echo resolve-echo})
                     schema/compile)
          service (lp/pedestal-service schema {:graphiql true})]
      (http/start service)
      (try
        (f)
        (finally
          (http/stop service))))))

(defn ^:private send-request
  "Sends a GraphQL request to the server and returns the response."
  ([query]
   (send-request :get query))
  ([method query]
   (send-request method query nil))
  ([method query vars]
   (-> {:method method
        :url "http://localhost:8888/graphql"
        :throw-exceptions false}
       (cond->
         (= method :get)
         (assoc-in [:query-params :query] query)

         (= method :post)
         (-> (assoc-in [:headers "Content-Type"] "application/graphql")
             (assoc :body query))

         vars
         (assoc-in [:query-params :variables] (cheshire/generate-string vars)))
       client/request
       (update :body #(try
                        (cheshire/parse-string % true)
                        (catch Exception t
                          %))))))

(deftest simple-get-query
  (let [response (send-request "{ echo(value: \"hello\") { value method }}")]
    (is (= 200 (:status response)))
    (is (= "application/json"
           (get-in response [:headers "Content-Type"])))
    (is (= {:data {:echo {:method "get"
                          :value "hello"}}}
           (:body response)))))

(deftest simple-post-query
  (let [response (send-request :post "{ echo(value: \"hello\") { value method }}")]
    (is (= 200 (:status response)))
    (is (= {:data {:echo {:method "post"
                          :value "hello"}}}
           (:body response)))))

(deftest status-set-by-error
  (let [response (send-request "{ echo(value: \"Baked.\", error: 420) { value }}")]
    (is (= {:body {:data {:echo {:value "Baked."}}
                   :errors [{:arguments {:error 420
                                         :value "Baked."}
                             :locations [{:column 0
                                          :line 1}]
                             :message "Forced error."
                             :query-path ["echo"]}]}
            :status 420}
           (select-keys response [:status :body])))))
