(ns pasquet.skullery.backend.schema
  "Contains custom resolvers and a function to provide the full schema."
  (:require
   [clojure.java.io :as io]
   [com.walmartlabs.lacinia.util :as util]
   [com.walmartlabs.lacinia.schema :as schema]
   [com.stuartsierra.component :as component]
   [pasquet.skullery.backend.db :as db]
   [clojure.edn :as edn]))


(defn product [db] (fn [_ args _] (db/from-db db {:id (:id args)} :products)))
(defn product-location [db] (fn [_ _ val] (db/from-db db {:id (:location val)} :locations)))
(defn product-unit [db] (fn [_ _ val] (db/from-db db {:id (:unit val)} :units)))
(defn product-variant [db] (fn [_ _ val] (db/from-db db {:product (:id val)} :variants)))
(defn variant-product [db] (fn [_ _ val] (db/from-db db {:id (:parent val)} :products)))
(defn variant-conversion [db] (fn [_ _ val] (db/from-db db {:from (:unit val) :to (:unit val)} :conversions)))
(defn conversion-from [db] (fn [_ _ val] (db/from-db db {:id (:from val)} :units)))
(defn conversion-to [db] (fn [_ _ val] (db/from-db db {:id (:to val)} :units)))

(defn products
  [db]
  (fn [_ args val]
    (let [{:keys [location id] :or {id (:id args)}} val]
      (db/from-db db {:id id :location location} products))))

(defn locations
  [db]
  (fn [_ args val]
    (println _)
    (let [{:keys [id] :or {id (:location val)}} args]
      (db/get-locations (:ds db) id))))

(defn units
  [db]
  (fn [_ args val]
    (let [{:keys [id] :or {id (:unit val)}} args]
      (db/get-units (:ds db) id))))

(defn variants
  [db]
  (fn [_ args val]
    (let [{product :id :or {product (:product args)}} val
          id (:id args)]
      (db/get-variants (:ds db) id product))))


(defn conversions
  [db]
  (fn [_ args val]
    '({:factor 22.5 :from_id 0 :to_id 1})))

(defn resolver-map
  [component]
  (let [db (:db component)]
    {:Product             (product            db)
     :Product/Location    (product-location   db)
     :Product/Unit        (product-unit       db)
     :Product/Variant     (product-variant    db)
     :Variant/Product     (variant-product    db)
     :Variant/Unit        (variant-unit       db)
     :Variant/Conversion  (variant-conversion db)
     :Conversion/FromUnit (conversion-from    db)
     :Conversion/ToUnit   (conversion-to      db)}))

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