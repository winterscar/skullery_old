(ns user
  (:require
   [pasquet.skullery.backend.system :as system]
   [com.walmartlabs.lacinia :as lacinia]
   [com.stuartsierra.component :as component]))

(defonce system nil)

(defn q
  [query-string]
  (-> system
      :schema-provider
      :schema
      (lacinia/execute query-string nil nil)))

(defn start
  []
  (alter-var-root #'system (fn [_]
                             (-> (system/new-system)
                                 component/start-system)))
  :started)

(defn stop
  []
  (when (some? system)
    (component/stop-system system)
    (alter-var-root #'system (constantly nil)))
  :stopped)

(comment
  (start)
  (stop))