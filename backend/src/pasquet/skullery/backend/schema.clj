(ns pasquet.skullery.backend.schema
  "Contains custom resolvers and a function to provide the full schema."
  (:require
   [clojure.java.io :as io]
   [com.walmartlabs.lacinia.util :as util]
   [com.walmartlabs.lacinia.schema :as schema]
   [com.stuartsierra.component :as component]
   [pasquet.skullery.backend.db :as db]
   [clojure.edn :as edn]))


(defn product            [db] (fn [_ args _] (db/get-products db (:id args))))
(defn product-variant    [db] (fn [_ _ val] (db/get-variants db (:id val))))
(defn variant-product    [db] (fn [_ _ val] (db/get-products db (:parent val))))
(defn variant-conversion [db] (fn [_ _ val] (db/get-conversions db (-> val :unit :id))))

(defn resolver-map
  [component]
  (let [db (:db component)]
    {:Product             (product            db)
     :Product/Variant     (product-variant    db)
     :Variant/Product     (variant-product    db)
     :Variant/Conversion  (variant-conversion db)}))

(defn load-schema
  [component]
  (-> (io/resource "schema.edn")
      slurp
      edn/read-string
      (util/attach-resolvers (resolver-map component))
      schema/compile))

(defrecord SchemaProvider [schema]

  component/Lifecycle

  (start [this]
    (assoc this :schema (load-schema this)))

  (stop [this]
    (assoc this :schema nil)))

(defn new-schema-provider
  []
  {:schema-provider (-> {}
                        map->SchemaProvider
                        (component/using [:db]))})