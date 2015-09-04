/*
# Licensed Materials - Property of IBM
# Copyright IBM Corp. 2015  
 */
package kafka;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;

import com.ibm.streamsx.topology.TStream;
import com.ibm.streamsx.topology.Topology;
import com.ibm.streamsx.topology.context.ContextProperties;
import com.ibm.streamsx.topology.context.StreamsContextFactory;
import com.ibm.streamsx.topology.function.Function;
import com.ibm.streamsx.topology.function.UnaryOperator;
import com.ibm.streamsx.topology.messaging.kafka.ConsumerConnector;
import com.ibm.streamsx.topology.messaging.kafka.ProducerConnector;
import com.ibm.streamsx.topology.tuple.Message;
import com.ibm.streamsx.topology.tuple.SimpleMessage;


/**
 * Demonstrate integrating with the Apache Kafka messaging system
 * <a href="http://kafka.apache.org">http://kafka.apache.org</a>.
 * <p>
 * Connectors are used to create a bridge between topology streams
 * and a Kafka cluster:
 * <ul>
 * <li>{@link com.ibm.streamsx.topology.messaging.kafka.ConsumerConnector ConsumerConnector} - subscribe to Kafka topics and create streams of messages.</li>
 * <li>{@link com.ibm.streamsx.topology.messaging.kafka.ProducerConnector ProducerConnector} - publish streams of messages to Kafka topics.</li>
 * </ul>
 * <p>
 * The sample publishes some messages to a Kafka topic.  
 * It also subscribes to the topic and reports the messages received.
 * The messages received may include messages from prior runs of the sample.
 * <p>
 * The sample requires a running Kafka cluster with the following
 * characteristics:
 * <ul>
 * <li>the kafka topic (@code kafkaSampleTopic} has been created.
 * e.g.<br>
 * {@code ${KAFKA_HOME}/bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic kafkaSampleTopic}
 * </li>
 * <li>the Kafka cluster's zookeeper connection is {@code localhost:2181}</li>
 * <li>the Kafka cluster's brokers addresses is {@code localhost:9092}</li>
 * </ul>
 * <p>
 * Required IBM Streams environment variables:
 * <ul>
 * <li>STREAMS_INSTALL - the Streams installation directory</li>
 * <li>STREAMS_DOMAIN_ID - the Streams domain to use for context {@code DISTRIBUTED}
 * <li>STREAMS_INSTANCE_ID - the Streams instance to use for context {@code DISTRIBUTED}
 * </ul>
 * <p>
 * See the Apache Kafka link above for information about setting up a Kafka
 * cluster and creating a topic.
 * <p>
 * This may be executed from the {@code samples/java/functional} directory as:
 * <UL>
 * <LI>{@code ant run.kafka.distributed} - Using Apache Ant, this will run in distributed mode.</li>
 * <LI>{@code ant run.kafka} - Using Apache Ant, this will run in standalone mode.</li>
 * <LI>
 * {@code java -cp functionalsamples.jar:../../../com.ibm.streamsx.topology/lib/com.ibm.streamsx.topology.jar:$STREAMS_INSTALL/lib/com.ibm.streams.operator.samples.jar
 *  kafka.KafkaSample CONTEXT_TYPE
 * } - Run directly from the command line.
 * </LI>
 * <i>CONTEXT_TYPE</i> is one of:
 * <UL>
 * <LI>{@code DISTRIBUTED} - Run as an IBM Streams distributed application.</LI>
 * <LI>{@code STANDALONE} - Run as an IBM Streams standalone application.</LI>
 * <LI>{@code BUNDLE} - Create an IBM Streams application bundle.</LI>
 * <LI>{@code TOOLKIT} - Create an IBM Streams application toolkit.</LI>
 * </UL>
 * <LI>
 * An application execution within your IDE once you set the class path to include the correct jars.</LI>
 * </UL>
 */
public class KafkaSample {
    private static final String ZOOKEEPER_CONNECT = "localhost:2181";    
    private static final String KAFKA_METADATA_BROKER_LIST = "localhost:9092";
    
    private static final String TOPIC = "kafkaSampleTopic";

    private static final int PUB_DELAY_MSEC = 5*1000;
    private static final String uniq = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
    private boolean captureArtifacts = false;
    private boolean setAppTracingLevel = false;
    private java.util.logging.Level appTracingLevel = java.util.logging.Level.FINE;
    private Map<String,Object> config = new HashMap<>();
    
    public static void main(String[] args) throws Exception {
        String contextType = "DISTRIBUTED";
        if (args.length > 0)
            contextType = args[0];
        System.out.println("\nREQUIRES:"
                + " Kafka topic " + TOPIC + " exists"
                + ", Kafka broker at " + KAFKA_METADATA_BROKER_LIST
                + ", Kafka zookeeper at " + ZOOKEEPER_CONNECT
                + "\n"
                );

        KafkaSample app = new KafkaSample();
        app.publishSubscribe(contextType);
    }
    
    /**
     * Publish some messages to a topic, scribe to the topic and report
     * received messages.
     * @param contextType string value of a {@code StreamsContext.Type}
     * @throws Exception
     */
    public void publishSubscribe(String contextType) throws Exception {
        
        setupConfig();
        Topology top = new Topology("kafkaSample");
        String groupId = newGroupId(top.getName());

        ProducerConnector producer = new ProducerConnector(top, createProducerConfig());
        ConsumerConnector consumer = new ConsumerConnector(top, createConsumerConfig(groupId));
        
        TStream<Message> msgs = makeStreamToPublish(top);

        // for the sample, give the consumer a chance to become ready
        msgs = msgs.modify(initialDelayFunc(PUB_DELAY_MSEC));

        producer.publish(msgs, TOPIC);
        
        TStream<Message> rcvdMsgs = consumer.subscribe(TOPIC);

        rcvdMsgs.print();  // show what we received

        // Execute the topology, to send and receive the messages.
        Future<?> future = StreamsContextFactory.getStreamsContext(contextType)
                .submit(top, config);
        
        if (contextType.contains("DISTRIBUTED")) {
            System.out.println("\nSee the job's PE console logs for the topology output.\n");
        }
        else if (contextType.contains("STANDALONE")
                || contextType.contains("EMBEDDED")) {
            Thread.sleep(15000);
            future.cancel(true);
        }
    }
    
    private Properties createConsumerConfig(String groupId) {
        Properties props = new Properties();
        props.put("zookeeper.connect", ZOOKEEPER_CONNECT);
        props.put("group.id", groupId);
        props.put("zookeeper.session.timeout.ms", "400");
        props.put("zookeeper.sync.time.ms", "200");
        props.put("auto.commit.interval.ms", "1000");
        return props;
    }
    
    private Properties createProducerConfig() {
        Properties props = new Properties();
        props.put("metadata.broker.list", KAFKA_METADATA_BROKER_LIST);
        props.put("serializer.class", "kafka.serializer.StringEncoder");
        props.put("request.required.acks", "1");
        return props;
    }
    
    @SuppressWarnings("serial")
    private static TStream<Message> makeStreamToPublish(Topology top) {
        return top.strings("Hello", "Are you there?",
                           "3 of 5", "4 of 5", "5 of 5"
                ).transform(new Function<String,Message>() {
                    private String timestamp;
                    @Override
                    public Message apply(String v) {
                        if (timestamp == null)
                            timestamp = new SimpleDateFormat("HH:mm:ss.SSS ").format(new Date());
                        return new SimpleMessage(timestamp + v);
                    }
                });
    }
    
    private void setupConfig() {
        if (captureArtifacts)
            config.put(ContextProperties.KEEP_ARTIFACTS, true);
        if (setAppTracingLevel)
            config.put(ContextProperties.TRACING_LEVEL, appTracingLevel);
    }
    
    private String newGroupId(String name) {
        // be insensitive to old consumers for the topic/groupId hanging around
        String groupId = name + "_" + uniq.replaceAll(":", "");
        System.out.println("Using Kafka consumer group.id " + groupId);
        return groupId;
    }

    @SuppressWarnings("serial")
    private static UnaryOperator<Message> initialDelayFunc(final int delayMsec) {
        return new UnaryOperator<Message>() {
            private int initialDelayMsec = delayMsec;
    
            @Override
            public Message apply(Message v) {
                if (initialDelayMsec != -1) {
                    try {
                        Thread.sleep(initialDelayMsec);
                    } catch (InterruptedException e) {
                        // done delaying
                    }
                    initialDelayMsec = -1;
                }
                return v;
            }
        };
    }
}
