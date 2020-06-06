(ns pasquet.skullery.backend.db
  (:require
   [com.stuartsierra.component :as component]
   [clojure.java.jdbc :as jdbc]
   [honeysql.core :as sql]
   [honeysql.helpers :refer :all]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :as psqlh])
  (:import (com.mchange.v2.c3p0 ComboPooledDataSource)))


(defn ^:private pooled-data-source
  [{:keys [host dbname user password port]}]
  {:datasource
   (doto (ComboPooledDataSource.)
     (.setDriverClass "org.postgresql.Driver")
     (.setJdbcUrl (str "jdbc:postgresql://" host ":" port "/" dbname))
     (.setUser user)
     (.setPassword password))})

(defrecord SkulleryDb [ds]

  component/Lifecycle

  (start [this]
    (assoc this
           :ds (pooled-data-source {:host "skullery-db" :dbname   "skullery"
                                    :user "chef"        :password "skullery"
                                    :port  5432})))

  (stop [this]
    (-> ds :datasource .close)
    (assoc this :ds nil)))

(defn new-db
  []
  {:db (map->SkulleryDb {})})


(defn list->single
  [list] (if (= 1 (count list)) (first list) list))

(defn query   [{db :ds} q] (->> (sql/format q) (jdbc/query db)))
(defn execute [{db :ds} q]
  (println (sql/format q))
  (->> (sql/format q) (jdbc/execute! db)))

(defn from-db
  "Fetch value(s) from the database table 'table'.
  Filter key value pairs will act as where clauses. eg. {:id 0} -> where id = 0."
  [db filters table]
  (let [q (as-> (select :*) k
            (reduce (fn [q f] (merge-where q [:= (key f) (val f)])) k filters)
            (from k table))
        result (query db q)]
    (list->single result)))


(defn get-conversions
  [db product unit]
  (let [q (-> (select :*)
              (from :conversions)
              (where [:and [:or [:= :a unit] [:= :b unit]]
                      [:= :product product]]))
        r (query db q)]
    r))

(defn cleanup-conversions
  "Remove 2-way conversions and simplify fractions."
  [])

(defn integrate-node
  "Returns a two SQL queries that will fully integrate the provided node with an existing (possibly empty) fully connected graph by attaching it to anchor.
   :connect-anchor->node will do what it says on the tin. It will simply create an entry in the conversions table from node to anchor with the provided ratios.
   :connect-others->node will connect every node connected to the anchor to the node. Running both these queries on every insert will ensure that the 'graphs' represented in conversions remains complete. That is to say, every node will be connected to every other, with two exeptions.
   1. Each product will maintain it's own graph. Because every node must be connected to every other, there are (node * node - 1)/2 edges, connecting every project would create a crazy amount of connections
   2. Within a single product, separate graphs may develop if there is no link between them. However, as soon as a single link is added, both graphs should be merged into a single fully connected graph. To see how that happens, see (add-conversion).
  In the context of skullery, this represents the addition of a conversion from node to anchor and all units that convert to anchor (the rest of the graph). 
  - n & a represent the ratio of the conversion where n nodes will yeild a anchors.
  - product limits the connections to a single product. (to avoid a massive conversion graph.)
  - conversions: the table in which to store the generated conversions."
  ([node anchor n-amt a-amt product conversions]
  ;; 
  ;; We need to maintain a complete graph here. We take advantage of the fact that the existing 
  ;; graph is already complete (because we always make it complete on insertions).
  ;; When we insert an edge between node and anchor, we know that every node in the graph
  ;; is already connected to anchor. However, anchor could be the 'a' side of that edge 
  ;; (node<-anchor->other) or the 'b' side of that edge.
  ;; 
  ;; In the case where anchor is the 'a' side of the link, we can calculate the ratio from 
  ;; each other node (other) -> current node (node) as follows:
  ;; 
  ;; let the edge from other to anchor be comprized of two values b and  a
  ;; let the edge from anchor to node be comprized of two values a' and  b'
  ;; where the cost of traversing the edge is the value at the end / start.
  ;; 
  ;; NB. cost is perhaps not the right term as we are infact refering to the amount of 
  ;; one end we will have when starting with start amount, but that's a bit verbose, so we say cost.
  ;; 
  ;; When inserting a node in the graph, we always insert it as the b side of the link, so in this 
  ;; case we're going b / a and then a' / b'
  ;; 
  ;; (other (b) -> (a) anchor (a') -> (b') node)
  ;; 
  ;; Bacause the costs reflect a value change (I had x amount, now I have y amount)
  ;; we can combine subsequent edge traversals by multiplying them.
  ;; 
  ;; Finally the cost from other (a) -> (b) node in this case would be (a / b) * ( b' / a').
  ;; This can also be written as (a * b') / (b * a')
  ;; 
  ;; Conversely when the anchor represents the the 'b' side o the edge,
  ;; 
  ;; let the edge from other to anchor be comprized of two values a and  b
  ;; let the edge from anchor to node be comprized of two values a' and  b
  ;; 
  ;; other (a) <- (b) anchor (a') -> (b') node
  ;; 
  ;; As per the logic above, other (a) -> (b) node 
  ;; can be calculated as (b / a) * (b' / a') also written as (b * b') / (a * a')
  ;; 
  ;; 
  ;; NB. in the variable names below -> represents an edge a -> b or b <- a.
   (let [node<-anchor->other  (-> (select product node :b
                                          (sql/call :* n-amt :a_amount)
                                          (sql/call :* a-amt :b_amount))
                                  (from conversions)
                                  (where [:and
                                          [:=  :a anchor]
                                          [:!= :b node]
                                          [:=  :product product]]))
         node<-anchor<-other  (-> (select product node :a
                                          (sql/call :* n-amt :b_amount)
                                          (sql/call :* a-amt :a_amount))
                                  (from conversions)
                                  (where [:and
                                          [:!= :a node]
                                          [:=  :b anchor]
                                          [:=  :product product]]))
         connect-anchor->node (-> (insert-into conversions)
                                  (values [{:product product
                                            :a anchor :b node
                                            :a_amount a-amt
                                            :b_amount n-amt}]))
         connect-others->node (-> (insert-into
                                   [[conversions [:product :a :b :a_amount :b_amount]]
                                    {:union-all [node<-anchor->other node<-anchor<-other]}])
                                  (psqlh/upsert (-> (psqlh/on-conflict)
                                                    (psqlh/do-nothing))))]
     {:connect-anchor->node connect-anchor->node
      :connect-others->node connect-others->node}))

  ([{:keys [:a :b :a_amount :b_amount]} product conversions]
   "This override will re-build the connections for an existing node, without attempting to re-add the existing edge to the graph. Useful when joining two separate graphs."
   (:connect-others->node (integrate-node b a b_amount a_amount product conversions))))

(comment 
  {:insert-into
   [[conversions [:product :a :b :a_amount :b_amount]]
    {:union-all [node<-anchor->other node<-anchor<-other]}]})
  
  (defn edges-of
    "Returns a SQL query that selects all the edges of a specific node.
   node: The node who's edges we're looking for.
   product: limit the edges to that of a single product.
   table: The name of the table containing the edges"
    [node product table]
    (-> (select :a :b :a_amount :b_amount)
        (from table)
        (where [:and
                [:or [:= :a node] [:= :b node]]
                [:= :product product]])))

(defn add-conversion
  "Adds a conversion between two units (a & b), and ensures all transitive conversions
   are also added.
   There is no guarantee that units a and b will be stored in the db as a and b respectively."
  [db unit-a unit-b a-amt b-amt product]
  (let [conversions :conversions ;The table conversions are stored in
        ;; Let's check if 'a' or 'b' are already part of the graph.
        ;; Usually, one side will be, and the other will be a disconnected node.
        ;; However, this may not always be the case. For example, if two separate sets of units
        ;; are finally joined together you may be attempting to merge together two graphs.
        ;; In the interest of speed, we want to connect the smaller graph to the bigger one.
        ;; We'll call the small graph secs, and the large graph prims.
        get-a-edges   (query db (edges-of unit-a product conversions))
        get-b-edges   (query db (edges-of unit-b product conversions))
        [secs prims]  (sort-by count [get-a-edges get-b-edges])
        ;; Now we know which side is larger, we'll take the a and b units we got in as 
        ;; args and bind them to sec and prim, representing the joining nodes on each
        ;; side of the edge we're inserting, with prim being part of the larger graph.
        [sec prim sec-amt prim-amt]
        (if (= prims get-a-edges) [unit-a unit-b a-amt b-amt] [unit-b unit-a b-amt a-amt])
        ;; Get the SQL we need to insert the conversion the user requested
        prim->sec    (integrate-node prim sec prim-amt sec-amt product conversions)
        ;; get the SQL we need to rebuild the secs graph. This may be an empty list if
        ;; secs was a single node. (Most of the time it will be).
        rebuild-secs (map #(integrate-node % product conversions) secs)]
    (doseq [q (vals prim->sec)] (execute db q))
    (doseq [edge rebuild-secs] (execute db edge))
    ;; The insert process can get a little messy... Fractions are not simplified, and duplicate 
    ;; paths may be created 
    (cleanup-conversions)))