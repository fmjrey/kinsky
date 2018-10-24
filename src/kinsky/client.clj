(ns kinsky.client
  "Small clojure shim on top of the Kafka client API
   See https://github.com/pyr/kinsky for example usage."
  (:require [clojure.edn           :as edn]
            [cheshire.core         :as json])
  (:import java.util.Collection
           java.util.Map
           java.util.concurrent.TimeUnit
           java.util.regex.Pattern
           org.apache.kafka.clients.consumer.ConsumerRebalanceListener
           org.apache.kafka.clients.consumer.ConsumerRecord
           org.apache.kafka.clients.consumer.ConsumerRecords
           org.apache.kafka.clients.consumer.KafkaConsumer
           org.apache.kafka.clients.consumer.OffsetAndMetadata
           org.apache.kafka.clients.producer.Callback
           org.apache.kafka.clients.producer.KafkaProducer
           org.apache.kafka.clients.producer.ProducerRecord
           org.apache.kafka.clients.producer.RecordMetadata
           org.apache.kafka.common.Node
           org.apache.kafka.common.PartitionInfo
           org.apache.kafka.common.TopicPartition
           org.apache.kafka.common.errors.WakeupException
           org.apache.kafka.common.serialization.Deserializer
           org.apache.kafka.common.serialization.Serializer
           org.apache.kafka.common.serialization.StringDeserializer
           org.apache.kafka.common.serialization.StringSerializer))

(defprotocol MetadataDriver
  "Common properties for all drivers"
  (partitions-for [this topic]
    "Retrieve partition ownership information for a topic.
     The result is a data representation of a
     [PartitionInfo](http://kafka.apache.org/090/javadoc/org/apache/kafka/common/PartitionInfo.html)
     list.
     The structure for a partition info map is:

         {:topic     \"t\"
          :partition 0
          :isr       [{:host \"x\" :id 0 :port 9092}]
          :leader    {:host \"x\" :id 0 :port 9092}
          :replicas  [{:host \"x\" :id 0 :port 9092}]"))

(defprotocol ConsumerDriver
  "Driver interface for consumers"
  (poll!          [this timeout]
    "Poll for new messages. Timeout in ms.
     The result is a data representation of a ConsumerRecords instance.

         {:partitions [[\"t\" 0] [\"t\" 1]]
          :topics     [\"t\"]
          :count      2
          :by-partition {[\"t\" 0] [{:key       \"k0\"
                                     :offset    1
                                     :partition 0
                                     :topic     \"t\"
                                     :value     \"v0\"}]
                         [\"t\" 1] [{:key       \"k1\"
                                     :offset    1
                                     :partition 1
                                     :topic     \"t\"
                                     :value     \"v1\"}]}
          :by-topic      {\"t\" [{:key       \"k0\"
                                  :offset    1
                                  :partition 0
                                  :topic     \"t\"
                                  :value     \"v0\"}
                                 {:key       \"k1\"
                                  :offset    1
                                  :partition 1
                                  :topic     \"t\"
                                  :value     \"v1\"}]}}")
  (stop!          [this] [this timeout]
    "Stop consumption.")
  (pause!         [this] [this topic-partitions]
    "Pause consumption.")
  (resume!        [this topic-partitions]
    "Resume consumption.")
  (unsubscribe!   [this]
    "Unsubscribe from currently subscribed topics.")
  (subscribe!     [this topics] [this topics listener]
    "Subscribe to a topic or list of topics.
     The topics argument can be:

     - A simple string or keyword when subscribing to a single topic
     - A regex pattern to subscribe to matching topics
     - A collection of strings or keywords

     The optional listener argument is either a callback
     function or an implementation of
     [ConsumerRebalanceListener](http://kafka.apache.org/090/javadoc/org/apache/kafka/clients/consumer/ConsumerRebalanceListener.html).

     When a function is supplied, it will be called on relance
     events with a map representing the event, see
     [kinsky.client/rebalance-listener](#var-rebalance-listener)
     for details on the map format.")
  (commit!         [this] [this topic-offsets]
    "Commit offsets for a consumer.
     The topic-offsets argument must be a list of maps of the form:

     ```
     {:topic     topic
      :partition partition
      :offset    offset
      :metadata  metadata}
     ```
     The topic and partition tuple must be unique across the whole list.")
  (wake-up!        [this]
    "Safely wake-up a consumer which may be blocking during polling.")
  (seek!           [this] [this topic-partition offset]
    "Overrides the fetch offsets that the consumer will use on the next poll")
  (position!      [this] [this topic-partition]
    "Get the offset of the next record that will be fetched (if a record with that offset exists).")
  (subscription   [this]
    "Currently assigned topics"))

(defprotocol ProducerDriver
  "Driver interface for producers"
  (send!          [this record] [this record cb] [this topic k v] [this topic k v cb]
    "Produce a record on a topic.
     The record argument must be a map with the following possible keys:
     `:topic`, `:key`, `:value`, and `:partition`.
     The optional cb argument is a callback function of 2 arguments:
      - a producer record map as per rm->data,
      - an exception thrown during the processing of a record.
     One of the two arguments will be nil depending on the send result.
     ")
  (flush!         [this]
    "Ensure that produced messages are flushed."))

(defprotocol GenericDriver
  (close!         [this] [this timeout]
    "Close this driver"))

(defn serializer
  "Yield an instance of a serializer from a function of two arguments:
   a topic and the payload to serialize."
  [f]
  (reify
    Serializer
    (close [this])
    (configure [this configs is-key?])
    (serialize [this topic payload]
      (f topic payload))))

(defn edn-serializer
  "Serialize as EDN."
  []
  (serializer
   (fn [_ payload] (some-> payload pr-str .getBytes))))

(defn json-serializer
  "Serialize as JSON through cheshire."
  []
  (serializer
   (fn [_ payload] (some-> payload json/generate-string .getBytes))))

(defn keyword-serializer
  "Serialize keywords to strings, useful for keys."
  []
  (serializer (fn [_ k] (some-> k name .getBytes))))

(defn string-serializer
  "Kafka's own string serializer."
  []
  (StringSerializer.))

(defn deserializer
  "Yield an instance of a deserializer from a function of two arguments:
   a topic and the payload to deserialize."
  [f]
  (reify
    Deserializer
    (close [this])
    (configure [this configs is-key?])
    (deserialize [this topic payload]
      (f topic payload))))

(defn edn-deserializer
  "Deserialize EDN."
  []
  (deserializer
   (fn [_ #^"[B" payload]
     (when payload
       (edn/read-string (String. payload "UTF-8"))))))

(defn json-deserializer
  "Deserialize JSON."
  []
  (deserializer
   (fn [_ #^"[B" payload]
     (when payload
       (json/parse-string (String. payload "UTF-8") true)))))

(defn keyword-deserializer
  "Deserialize a string and then keywordize it."
  []
  (deserializer (fn [_ #^"[B" k] (when k (keyword (String. k "UTF-8"))))))

(defn string-deserializer
  "Kafka's own string deserializer"
  []
  (StringDeserializer.))

(def deserializers
  {:edn     edn-deserializer
   :keyword keyword-deserializer
   :string  string-deserializer
   :json    json-deserializer})

(def serializers
  {:edn     edn-serializer
   :keyword keyword-serializer
   :string  string-serializer
   :json    json-serializer})

(defn ^Deserializer ->deserializer
  [x]
  (cond
    (keyword? x) (if-let [f (deserializers x)]
                   (f)
                   (throw (ex-info "unknown deserializer alias" {})))
    (fn? x)      (x)
    :else        x))

(defn ^Serializer ->serializer
  [x]
  (cond
    (keyword? x) (if-let [f (serializers x)]
                   (f)
                   (throw (ex-info "unknown serializer alias" {})))
    (fn? x)      (x)
    :else        x))

(defn ^Map opts->props
  "Kakfa configs are now maps of strings to strings. Morph
   an arbitrary clojure map into this representation."
  [opts]
  (into {} (for [[k v] opts] [(name k) (str v)])))

(defn ^ConsumerRebalanceListener rebalance-listener
  "Wrap a callback to yield an instance of a Kafka ConsumerRebalanceListener.
   The callback is a function of one argument, a map containing the following
   keys: :event, :topic and :partition. :event will be either :assigned or
   :revoked."
  [callback]
  (if (instance? ConsumerRebalanceListener callback)
    callback
    (let [->part  (fn [^TopicPartition p] {:topic (.topic p) :partition (.partition p)})
          ->parts (fn [ps m] (assoc m :partitions (mapv ->part ps)))]
      (reify
        ConsumerRebalanceListener
        (onPartitionsAssigned [this partitions]
          (callback
           (->parts partitions {:event :assigned})))
        (onPartitionsRevoked [this partitions]
          (callback
           (->parts partitions {:event :revoked})))))))

(defn ->topic-partition
  "Yield a TopicPartition from a clojure map."
  [{:keys [topic partition]}]
  (TopicPartition. (name topic) (int partition)))

(defn ->offset-metadata
  "Yield a OffsetAndMetadata from a clojure map."
  [{:keys [offset metadata]}]
  (OffsetAndMetadata. offset metadata))

(defn node->data
  "Yield a clojure representation of a node."
  [^Node n]
  {:host (.host n)
   :id   (.id n)
   :port (long (.port n))})

(defn partition-info->data
  "Yield a clojure representation of a partition-info."
  [^PartitionInfo pi]
  {:isr       (mapv node->data (.inSyncReplicas pi))
   :leader    (node->data (.leader pi))
   :partition (long (.partition pi))
   :replicas  (mapv node->data (.replicas pi))
   :topic     (.topic pi)})

(defn topic-partition->data
  "Yield a clojure representation of a topic-partition"
  [^TopicPartition tp]
  {:partition (.partition tp)
   :topic     (.topic tp)})

(def record-xform
  "A transducer to explode grouped records into individual
   entities.

   When sucessful, the output of kinsky.client/poll! takes the
   form:

       {:partitions   [[\"t\" 0] [\"t\" 1]]
        :topics       #{\"t\"}
        :count        2
        :by-partition {[\"t\" 0] [{:key       \"k0\"
                                   :offset    1
                                   :partition 0
                                   :topic     \"t\"
                                   :value     \"v0\"}]
                       [\"t\" 1] [{:key       \"k1\"
                                   :offset    1
                                   :partition 1
                                   :topic     \"t\"
                                   :value     \"v1\"}]}
        :by-topic      {\"t\" [{:key       \"k0\"
                                :offset    1
                                :partition 0
                                :topic     \"t\"
                                :value     \"v0\"}
                               {:key       \"k1\"
                                :offset    1
                                :partition 1
                                :topic     \"t\"
                                :value     \"v1\"}]}}

   To make working with the output channel easier, this
   transducer morphs these messages into a list of
   distinct records:

       ({:key \"k0\" :offset 1 :partition 0 :topic \"t\" :value \"v0\"}
        {:key \"k1\" :offset 1 :partition 1 :topic \"t\" :value \"v1\"}
        ...)
  "
  (comp (map :by-partition) (mapcat vals) cat))

(defn cr->data
  "Yield a clojure representation of a consumer record"
  [^ConsumerRecord cr]
  {:key       (.key cr)
   :offset    (.offset cr)
   :partition (.partition cr)
   :topic     (.topic cr)
   :value     (.value cr)})

(defn consumer-records->data
  "Yield the clojure representation of topic"
  [^ConsumerRecords crs]
  (let [->d  (fn [^TopicPartition p] [(.topic p) (.partition p)])
        ps   (.partitions crs)
        ts   (set (for [^TopicPartition p ps] (.topic p)))
        by-p (into {} (for [^TopicPartition p ps] [(->d p) (mapv cr->data (.records crs p))]))
        by-t (into {} (for [^String t ts] [t (mapv cr->data (.records crs t))]))]
    {:partitions   (vec (for [^TopicPartition p ps] [(.topic p) (.partition p)]))
     :topics       ts
     :count        (.count crs)
     :by-topic     by-t
     :by-partition by-p}))

(defn ^Collection ->topics
  "Yield a valid topic object for subscription given a string, keyword,
  regex pattern or collection of strings or keywords."
  [topics]
  (assert (or (string? topics)
              (keyword? topics)
              (instance? Pattern topics)
              (and (instance? Collection topics)
                   (every? (some-fn string? keyword?) topics)))
          (str "topics argument must be a string, keyword, regex pattern or "
               "collection of strings or keywords, received " topics))
  (cond
    (keyword? topics)             [(name topics)]
    (string? topics)              [topics]
    (instance? Collection topics) (mapv name topics)
    (instance? Pattern topics)    topics
    :else (throw (ex-info "topics argument is invalid" {:topics topics}))))

(defn consumer->driver
  "Given a KafkaConsumer and an optional callback to call when stopping,
   yield a consumer driver.

   The consumer driver implements the following protocols:

   - [ConsumerDriver](#var-ConsumerDriver)
   - [MetadataDriver](#var-MetadataDriver)
   - `clojure.lang.IDeref`: `deref` to access underlying
     [KafkaConsumer](http://kafka.apache.org/090/javadoc/org/apache/kafka/clients/producer/KafkaConsumer.html)
     instance."
  ([^KafkaConsumer consumer]
   (consumer->driver consumer nil))
  ([^KafkaConsumer consumer run-signal]
   (reify
     ConsumerDriver
     (poll! [this timeout]
       (consumer-records->data (.poll consumer timeout)))
     (stop! [this]
       (stop! this 0))
     (stop! [this timeout]
       (when run-signal
         (run-signal))
       (.wakeup consumer))
     (pause! [this topic-partitions]
       (.pause consumer
               (map ->topic-partition topic-partitions)))
     (resume! [this topic-partitions]
       (.resume consumer
                (map ->topic-partition topic-partitions)))
     (subscribe! [this topics]
       (.subscribe consumer (->topics topics)))
     (subscribe! [this topics listener]
       (.subscribe consumer (->topics topics) (rebalance-listener listener)))
     (unsubscribe! [this]
       (.unsubscribe consumer))
     (wake-up! [this]
       (.wakeup consumer))
     (commit! [this]
       (.commitSync consumer))
     (commit! [this topic-offsets]
       (.commitSync consumer
                    (->> topic-offsets
                         (map (juxt ->topic-partition ->offset-metadata))
                         (reduce merge {}))))
    (seek! [this topic-partition offset]
        (.seek consumer (->topic-partition topic-partition) offset))
    (position! [this topic-partition]
      (.position consumer (->topic-partition topic-partition)))
    (subscription [this]
      (.subscription consumer))
    GenericDriver
    (close! [this]
      (.close consumer))
    MetadataDriver
    (partitions-for [this topic]
      (mapv partition-info->data (.partitionsFor consumer topic)))
    clojure.lang.IDeref
    (deref [this]
      consumer))))

(defn safe-poll!
  "Implementation of poll which disregards wake-up exceptions"
  [consumer timeout]
  (try
    (poll! consumer timeout)
    (catch WakeupException _)))

(defn rm->data
  "Yield a clojure representation of a producer RecordMetadata.
  The map returned is in the form:
  {:topic     \"topic-name\"
   :partition 0 ;; nil if unknown
   :offset    1234567890
   :timestamp 9876543210}"
  [^RecordMetadata rm]
  {:topic     (.topic rm)
   :partition (let [partition (.partition rm)]
                (when (not= partition RecordMetadata/UNKNOWN_PARTITION)
                  partition))
   :offset    (.offset rm)
   :timestamp (.timestamp rm)})

(defn ^ProducerRecord ->record
  "Build a ProducerRecord from a clojure map, or given topic, key, value.
   Return ProducerRecord instances untouched."
  ([payload]
   (if (instance? ProducerRecord payload)
     payload
     (let [{:keys [partition key value]} payload
           topic                         (some-> payload :topic name)]
       (cond
         (nil? topic)
         (throw (ex-info "Need a topic to send to" {:payload payload}))

         (and key partition)
         (ProducerRecord. (str topic) (int partition) key value)

         :else
         (->record topic key value)))))
  ([topic k v]
   (cond
     (nil? topic)
     (throw (ex-info "Need a topic to send to" {:topic topic :key k :value v}))

     key
     (ProducerRecord. (str topic) k v)

     :else
     (ProducerRecord. (str topic) v))))


(defn ^Callback ->callback
  "Return a producer Callback instance given a function taking 2 arguments,
    - a producer record map as per rm->data,
    - an exception thrown during the processing of a record.
   One of the two argument will be nil depending on the send result."
  [f]
  (when f
    (reify
      Callback
      (onCompletion [_ record-metadata exception]
        (f (some-> record-metadata rm->data) exception)))))

(defn producer->driver
  "Yield a driver from a Kafka Producer.
   The producer driver implements the following protocols:

   - [ProducerDriver](#var-ProducerDriver)
   - [MetadataDriver](#var-MetadataDriver)
   - `clojure.lang.IDeref`: `deref` to access underlying
     [KafkaProducer](http://kafka.apache.org/090/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html)
     instance."
  [^KafkaProducer producer]
  (reify
    GenericDriver
    (close! [this]
      (.close producer))
    (close! [this timeout]
      (if (nil? timeout)
        (.close producer)
        (.close producer (long timeout) TimeUnit/MILLISECONDS)))
    ProducerDriver
    (send! [this record]
      (send! this record nil))
    (send! [this record cb]
      (.send producer (->record record) (->callback cb)))
    (send! [this topic k v]
      (send! this topic k v nil))
    (send! [this topic k v cb]
      (.send producer (->record topic k v) (->callback cb)))
    (flush! [this]
      (.flush producer))
    MetadataDriver
    (partitions-for [this topic]
      (mapv partition-info->data (.partitionsFor producer topic)))
    clojure.lang.IDeref
    (deref [this]
      producer)))

(defn producer
  "Create a producer from a configuration and optional serializers.
   If a single serializer is provided, it will be used for both keys
   and values. If none are provided, the configuration is expected to
   hold serializer class names."
  ([config]
   (producer->driver (KafkaProducer. (opts->props config))))
  ([config serializer]
   (producer->driver (KafkaProducer. (opts->props config)
                                     (->serializer serializer)
                                     (->serializer serializer))))
  ([config kserializer vserializer]
   (producer->driver (KafkaProducer. (opts->props config)
                                     (->serializer kserializer)
                                     (->serializer vserializer)))))

(defn consumer
  "Create a consumer from a configuration and optional deserializers.
   If a callback is given, call it when stopping the consumer.
   If deserializers are provided, use them otherwise expect deserializer
   class name in the config map."
  ([config]
   (consumer->driver (KafkaConsumer. (opts->props config))))
  ([config callback]
   (consumer->driver (KafkaConsumer. (opts->props config))
                     callback))
  ([config kdeserializer vdeserializer]
   (consumer->driver (KafkaConsumer. (opts->props config)
                                     (->deserializer kdeserializer)
                                     (->deserializer vdeserializer))))
  ([config callback kdeserializer vdeserializer]
   (consumer->driver (KafkaConsumer. (opts->props config)
                                     (->deserializer kdeserializer)
                                     (->deserializer vdeserializer))
                     callback)))
