(ns pasquet.skullery.backend.system
  (:require
   [com.stuartsierra.component :as component]
   [pasquet.skullery.backend.schema :as schema]
   [pasquet.skullery.backend.db :as db]
   [pasquet.skullery.backend.server :as server]))

(defn new-system
  []
  (merge (component/system-map)
         (server/new-server)
         (schema/new-schema-provider)
         (db/new-db)))