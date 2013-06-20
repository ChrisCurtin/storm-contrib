NOTE: THIS IS A WORK IN PROGRESS

If you are looking for integration with Kafka 0.8.0 and Storm 0.9.0 this is a partial working Spout. Somethings that need
work:
1. the emitter doesn't know about the maximum number of tuples Storm expects per call. Need to detect that and
   only emit what Storm can handle. This might lead to messages being lost.

2. The start from beginning logic might have an issue with finding the offset. Should be calling Kafka to find the offset
   instead of using 0 or -1.

3. The keys in ZK are wrong. In 0.8.0 Kafka can now move the leader between brokers, so the key that used to be the broker name
   and topic/partition isn't valid if the leader changes. Should rename the key to be the topic and parition then use
   the Kafka APIs to find the real leader on startup/leader change

4. It has only been tested with Trident topologies. Not sure if it works with 'regular' Storm.

5. confirm that the offset of the messages read in the fetch() calls are what you asked for. Turns out Kafka may include earlier messages if compression is being used

6. Retry logic for Leader reassignment feels 'wrong' In too many places. Need to rethink how that works.


ORIGINAL file starts here:


storm-kafka provides a regular spout implementation and a TransactionalSpout implementation for Apache Kafka 0.7.

storm-kafka is available from Maven [here](http://clojars.org/storm/storm-kafka).

## Using KafkaSpout

`KafkaSpout` is a regular spout implementation that reads from a Kafka cluster. The basic usage is like this:

```java
SpoutConfig spoutConfig = new SpoutConfig(
  ImmutableList.of("kafkahost1", "kafkahost2"), // list of Kafka brokers
  8, // number of partitions per host
  "clicks", // topic to read from
  "/kafkastorm", // the root path in Zookeeper for the spout to store the consumer offsets
  "discovery"); // an id for this consumer for storing the consumer offsets in Zookeeper
KafkaSpout kafkaSpout = new KafkaSpout(spoutConfig);
```

Currently the spout is parameterized with a static list of brokers and a fixed number of partitions per host – we'd like to make this dynamic in the future.

The spout stores the state of the offsets its consumed in Zookeeper. The spout is parameterized with the root path to store the offsets and an id for this particular spout. So offsets for partitions will be stored in these paths, where "0", "1" are ids for the partitions:

```
{root path}/{id}/0
{root path}/{id}/1
{root path}/{id}/2
{root path}/{id}/3
...
```

By default, the offsets will be stored in the same Zookeeper cluster that Storm uses. You can override this via your spout config like this:

```java
spoutConfig.zkServers = ImmutableList.of("otherserver.com");
spoutConfig.zkPort = 2191;
```

Another very useful config in the spout is the ability to force the spout to rewind to a previous offset. You do `forceStartOffsetTime` on the spout config, like so:

```java
spoutConfig.forceStartOffsetTime(-2);
```

It will choose the latest offset written around that timestamp to start consuming. You can force the spout to always start from the latest offset by passing in -1, and you can force it to start from the earliest offset by passing in -2.

Finally, `SpoutConfig` has options for adjusting how the spout fetches messages from Kafka (buffer sizes, amount of messages to fetch at a time, timeouts, etc.).

## TODO

1. Add blacklisting of Kafka servers in `KafkaSpout` and `OpaqueTransactionalKafkaSpout`
2. Discover Kafka brokers dynamically through Zookeeper instead of using a static list of brokers
3. Discover number of partitions / topic / host dynamically
