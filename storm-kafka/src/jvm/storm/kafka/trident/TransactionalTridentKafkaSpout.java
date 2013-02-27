package storm.kafka.trident;

import backtype.storm.Config;
import backtype.storm.metric.api.CombinedMetric;
import backtype.storm.metric.api.MeanReducer;
import backtype.storm.metric.api.ReducedMetric;
import backtype.storm.task.TopologyContext;
import backtype.storm.tuple.Fields;

import java.util.*;

import kafka.api.FetchRequest;
import kafka.api.FetchRequestBuilder;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.javaapi.message.ByteBufferMessageSet;
import kafka.message.MessageAndOffset;
import storm.kafka.BrokerData;
import storm.kafka.DynamicPartitionConnections;
import storm.kafka.GlobalPartitionId;
import storm.trident.operation.TridentCollector;
import storm.trident.spout.IPartitionedTridentSpout;
import storm.trident.topology.TransactionAttempt;


public class TransactionalTridentKafkaSpout implements IPartitionedTridentSpout<Map<String, BrokerData>, GlobalPartitionId, Map> {
    
    TridentKafkaConfig _config;
    String _topologyInstanceId = UUID.randomUUID().toString();
   // public static final Logger LOG = LoggerFactory.getLogger(TransactionalTridentKafkaSpout.class);

    public TransactionalTridentKafkaSpout(TridentKafkaConfig config) {
        _config = config;
    }
    
    class Coordinator implements IPartitionedTridentSpout.Coordinator<Map> {
        IBrokerReader reader;
        
        public Coordinator(Map conf) {
            reader = KafkaUtils.makeBrokerReader(conf, _config);
        }
        
        @Override
        public void close() {
            _config.coordinator.close();
        }

        @Override
        public boolean isReady(long txid) {
            return _config.coordinator.isReady(txid);
        }

        @Override
        public Map getPartitionsForBatch() {
           return reader.getCurrentBrokers();
        }
    }
    
    class Emitter implements IPartitionedTridentSpout.Emitter<Map<String, BrokerData>, GlobalPartitionId, Map> {
        DynamicPartitionConnections _connections;
        String _topologyName;
        TopologyContext _context;
        KafkaUtils.KafkaOffsetMetric _kafkaOffsetMetric;
        ReducedMetric _kafkaMeanFetchLatencyMetric;
        CombinedMetric _kafkaMaxFetchLatencyMetric;

        public Emitter(Map conf, TopologyContext context) {
            _connections = new DynamicPartitionConnections(_config);
            _topologyName = (String) conf.get(Config.TOPOLOGY_NAME);
            _context = context;
            _kafkaOffsetMetric = new KafkaUtils.KafkaOffsetMetric(_config.topic, _connections,_config.clientName);
            context.registerMetric("kafkaOffset", _kafkaOffsetMetric, 60);
            _kafkaMeanFetchLatencyMetric = context.registerMetric("kafkaFetchAvg", new MeanReducer(), 60);
            _kafkaMaxFetchLatencyMetric = context.registerMetric("kafkaFetchMax", new MaxMetric(), 60);
        }
        
        @Override
        public Map emitPartitionBatchNew(TransactionAttempt attempt, TridentCollector collector, GlobalPartitionId partition, Map lastMeta) {
            SimpleConsumer consumer = _connections.register(partition);
            Map ret = KafkaUtils.emitPartitionBatchNew(_config, consumer, partition, collector, lastMeta, _topologyInstanceId, _topologyName, _kafkaMeanFetchLatencyMetric, _kafkaMaxFetchLatencyMetric);
            _kafkaOffsetMetric.setLatestEmittedOffset(partition, (Long)ret.get(KafkaUtils.META_START_OFFSET));
            return ret;
        }

        @Override
        public void emitPartitionBatch(TransactionAttempt attempt, TridentCollector collector, GlobalPartitionId partition, Map meta) {
            String instanceId = (String) meta.get(KafkaUtils.META_INSTANCE_ID);
            if(!_config.forceFromStart || instanceId.equals(_topologyInstanceId)) {
                KafkaUtils.debugMeta("emitPartitionBatch", meta);
                SimpleConsumer consumer = _connections.register(partition);
                long offset = (Long) meta.get(KafkaUtils.META_START_OFFSET);
                long endingOffset = (Long) meta.get(KafkaUtils.META_END_OFFSET);
                long start = System.nanoTime();
                FetchRequest req = new FetchRequestBuilder()
                        .clientId(_config.clientName)
                        .addFetch(_config.topic, partition.partition,offset, _config.fetchSizeBytes)
                        .build();
                ByteBufferMessageSet msgs = consumer.fetch(req).messageSet(_config.topic, partition.partition );
                long end = System.nanoTime();
                long millis = (end - start) / 1000000;
                _kafkaMeanFetchLatencyMetric.update(millis);
                _kafkaMaxFetchLatencyMetric.update(millis);

                for(MessageAndOffset msg: msgs) {
                    if(offset > endingOffset) break;
                    KafkaUtils.emit(_config, collector, msg.message());
                    offset = msg.offset();
                }        
            }
        }


        @Override
        public void close() {
            _connections.clear();
        }

        @Override
        public List<GlobalPartitionId> getOrderedPartitions(Map<String, BrokerData> partitions) {
            return KafkaUtils.getOrderedPartitions(partitions);
        }

        @Override
        public void refreshPartitions(List<GlobalPartitionId> list) {
            _connections.clear();
            _kafkaOffsetMetric.refreshPartitions(new HashSet<GlobalPartitionId>(list));
        }
    }
    

    @Override
    public IPartitionedTridentSpout.Coordinator getCoordinator(Map conf, TopologyContext context) {
        return new Coordinator(conf);
    }

    @Override
    public IPartitionedTridentSpout.Emitter<Map<String, BrokerData>, GlobalPartitionId, Map> getEmitter(Map conf, TopologyContext context) {
        return new Emitter(conf, context);
    }

    @Override
    public Fields getOutputFields() {
        return _config.scheme.getOutputFields();
    }
        
    @Override
    public Map<String, Object> getComponentConfiguration() {
        return null;
    }
}