package kafka.examples.consumer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Consumer<K extends Serializable, V extends Serializable> implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(Consumer.class);
	private final String groupId;
	private final String clientId;
	
	private List<String> topics;
	private AtomicBoolean closed = new AtomicBoolean();
	private CountDownLatch shutdownLatch = new CountDownLatch(1);
	
	public Consumer(String groupId, String clientId, List<String> topics) {

		if(groupId == null || groupId.isEmpty())
			throw new IllegalArgumentException("GroupId cannot be null / empty");

		if(clientId == null || clientId.isEmpty())
			throw new IllegalArgumentException("ClientId cannot not be null / empty");

		if(topics == null || topics.isEmpty())
			throw new IllegalArgumentException("Topics cannot be  null / empty"); 

		this.groupId = groupId;
		this.clientId = clientId;
		this.topics = topics;
	}
	
	private Properties getConsumerProperties() {
		Properties props = new Properties();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092");
		props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArrayDeserializer");
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArrayDeserializer");
		props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 3000);
		props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
		return props;
	}
	
	private void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			logger.error("C : {}, Error", e);
		}
	}
	
	@Override
	public void run() {
	
		logger.info("Starting consumer : {}", clientId);
		Properties configs = getConsumerProperties();
		final KafkaConsumer<K, V> consumer = new KafkaConsumer<>(configs, new CustomDeserializer<K>(), new CustomDeserializer<V>());
		
		ExecutorService executor = Executors.newSingleThreadExecutor(new CustomFactory("Dispatcher-" + clientId));
		final Map<TopicPartition, Long> partitionToUncommittedOffsetMap = new ConcurrentHashMap<>();
		final AtomicBoolean canRevoke = new AtomicBoolean(false);
		
		ConsumerRebalanceListener listener = new ConsumerRebalanceListener() {

			@Override
			public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
				canRevoke.compareAndSet(false, true);
				logger.info("C : {}, Revoked topicPartitions : {}", clientId, partitions);
				commitOffsets(consumer, partitionToUncommittedOffsetMap);
			}

			@Override
			public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
				for (TopicPartition tp : partitions) {
					OffsetAndMetadata offsetAndMetaData = consumer.committed(tp);
					long startOffset = offsetAndMetaData != null ? offsetAndMetaData.offset() : -1L;
					logger.info("C : {}, Assigned topicPartion : {} offset : {}", clientId, tp, startOffset);

					if(startOffset >= 0)
						consumer.seek(tp, startOffset);
				}
			}
		};
		
		consumer.subscribe(topics, listener);
		logger.info("Started to process records for consumer : {}", clientId);
		
		while(!closed.get()) {
			
			ConsumerRecords<K, V> records = consumer.poll(1000);
			
			if(records == null || records.isEmpty()) {
				logger.info("C: {}, Found no records, Sleeping for a while", clientId);
				sleep(500);
				continue;
			}
			
			/**
			 * After receiving the records, pause all the partitions and do heart-beat manually
			 * to avoid the consumer instance gets kicked-out from the group by the consumer coordinator
			 * due to the delay in the processing of messages
			 */
			consumer.pause(consumer.assignment().toArray(new TopicPartition[0]));
			Future<Boolean> future = executor.submit(new ConsumeRecords(records, partitionToUncommittedOffsetMap));
			
			Boolean isCompleted = false;
			int counter = 0;
			while(!isCompleted && !closed.get()) {
				try	{
					isCompleted = future.get(3, TimeUnit.SECONDS); // wait up-to heart-beat interval
				} catch (TimeoutException e) {
					
					if(canRevoke.get()) {
						canRevoke.set(false);
						if(counter > 0) { 
							future.cancel(true);
							break;
						}
					}
					
					logger.debug("C : {}, heartbeats the coordinator", clientId);
					consumer.poll(0); // does heart-beat
					counter++;
				} catch (Exception e) {
					logger.error("C : {}, Error while consuming records", clientId, e);
					break;
				}
			}
			consumer.resume(consumer.assignment().toArray(new TopicPartition[0]));
			commitOffsets(consumer, partitionToUncommittedOffsetMap);
		}
		
		try {
			executor.shutdownNow();
			while(!executor.awaitTermination(5, TimeUnit.SECONDS));
		} catch (InterruptedException e) {
			logger.error("C : {}, Error while exiting the consumer", clientId, e);
		}
		consumer.close();
		shutdownLatch.countDown();
		logger.info("C : {}, consumer exited", clientId);
	}

	private void commitOffsets(KafkaConsumer<K, V> consumer, Map<TopicPartition, Long> partitionToOffsetMap) {

		if(!partitionToOffsetMap.isEmpty()) {
			Map<TopicPartition, OffsetAndMetadata> partitionToMetadataMap = new HashMap<>();
			for(Entry<TopicPartition, Long> e : partitionToOffsetMap.entrySet()) {
				partitionToMetadataMap.put(e.getKey(), new OffsetAndMetadata(e.getValue() + 1));
			}
			
			logger.info("C : {}, committing the offsets : {}", clientId, partitionToMetadataMap);
			consumer.commitSync(partitionToMetadataMap);
			partitionToOffsetMap.clear();
		}
	}

	public void close() {
		try {
			closed.set(true);
			shutdownLatch.await();
		} catch (InterruptedException e) {
			logger.error("Error", e);
		}
	}
	
	private class ConsumeRecords implements Callable<Boolean> {
		
		ConsumerRecords<K, V> records;
		Map<TopicPartition, Long> partitionToUncommittedOffsetMap;
		
		public ConsumeRecords(ConsumerRecords<K, V> records, Map<TopicPartition, Long> partitionToUncommittedOffsetMap) {
			this.records = records;
			this.partitionToUncommittedOffsetMap = partitionToUncommittedOffsetMap;
		}
		
		@Override
		public Boolean call() {

			logger.info("C: {}, Number of records received : {}", clientId, records.count());
			try {
				for(ConsumerRecord<K, V> record : records) {
					TopicPartition tp = new TopicPartition(record.topic(), record.partition());
					logger.info("C : {}, Record received topicPartition : {} offset : {}", clientId, tp, record.offset());
					partitionToUncommittedOffsetMap.put(tp, record.offset());
					Thread.sleep(100); // Adds more processing time for a record
				}
			} catch (InterruptedException e) {
				logger.info("C : {}, Record consumption interrupted!", clientId);
			} catch (Exception e) {
				logger.error("Error while consuming", e);
			}
			return true;
		}
	}
	
	private static class CustomDeserializer<T extends Serializable> implements Deserializer<T> {
		
		@Override
		public void configure(Map<String, ?> configs, boolean isKey) {
		}

		@SuppressWarnings("unchecked")
		@Override
		public T deserialize(String topic, byte[] objectData) {
			return (objectData == null) ? null : (T) SerializationUtils.deserialize(objectData);
		}

		@Override
		public void close() {
		}
	}
	
	private static class CustomFactory implements ThreadFactory {

		private String threadPrefix;
		private int counter = 0;
		
		public CustomFactory(String threadPrefix) {
			this.threadPrefix = threadPrefix;
		}
		
		@Override
		public Thread newThread(Runnable r) {
			return new Thread(r, threadPrefix + "-" + counter++);
		}
	}
	
	public static void main(String[] args) throws InterruptedException {
		
		List<Consumer<String, Integer>> consumers = new ArrayList<>();
		for (int i=0; i<3; i++) {
			String clientId = "Worker" + i;
			Consumer<String, Integer> consumer = new Consumer<>("consumer-group", clientId, Arrays.asList("test"));
			consumers.add(consumer);
		}
		
		ExecutorService executor = Executors.newFixedThreadPool(consumers.size());
		
		// New consumer added to the group every 30 secs
		for (Consumer<String, Integer> consumer : consumers) {
			executor.execute(consumer);
			Thread.sleep(TimeUnit.SECONDS.toMillis(30)); // let the consumer run for half-a-minute
		}
		
		Thread.sleep(TimeUnit.SECONDS.toMillis(60)); // let all the consumers run for few minutes

		// Close the consumer one by one
		for (Consumer<String, Integer> consumer : consumers) {
			Thread.sleep(TimeUnit.SECONDS.toMillis(30));
			consumer.close();
		}
		
		executor.shutdown();
		while(!executor.awaitTermination(5, TimeUnit.SECONDS));
		logger.info("Exiting the application");
	}
}


/**
 * $Log$
 *  
 */
