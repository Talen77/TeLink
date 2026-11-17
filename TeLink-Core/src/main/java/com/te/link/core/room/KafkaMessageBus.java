package com.te.link.core.room;

import com.te.link.core.config.KafkaConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class KafkaMessageBus implements MessageBus {
    private static final Logger log = LoggerFactory.getLogger(KafkaMessageBus.class);

    private final KafkaProducer<String, String> producer;
    private final String bootstrapServers;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<KafkaConsumer<String, String>> consumers = new CopyOnWriteArrayList<>();

    public KafkaMessageBus(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
        this.producer = new KafkaProducer<>(producerProperties(bootstrapServers));
    }

    public static KafkaMessageBus fromSystemProperties() {
        String servers = System.getProperty("telink.kafka.bootstrap", KafkaConfig.SERVERS_CONFIG);
        return new KafkaMessageBus(servers);
    }

    @Override
    public void publish(String topic, String key, String value, PublishListener listener) {
        producer.send(new ProducerRecord<>(topic, key, value), (metadata, exception) -> {
            if (exception == null) {
                listener.onSuccess();
            } else {
                listener.onFailure(exception);
            }
        });
    }

    @Override
    public AutoCloseable subscribe(String topic, String group, MessageHandler handler) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties(bootstrapServers, group));
        consumers.add(consumer);
        Thread thread = new Thread(() -> consume(consumer, topic, handler), "telink-kafka-" + group);
        thread.setDaemon(true);
        thread.start();
        return () -> consumer.wakeup();
    }

    @Override
    public void close() {
        running.set(false);
        for (KafkaConsumer<String, String> consumer : consumers) {
            consumer.wakeup();
        }
        producer.close(Duration.ofSeconds(5));
    }

    private void consume(KafkaConsumer<String, String> consumer, String topic, MessageHandler handler) {
        try {
            consumer.subscribe(List.of(topic));
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                if (records.isEmpty()) {
                    continue;
                }
                boolean failed = false;
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        handler.onMessage(record.key(), record.value());
                    } catch (RuntimeException exception) {
                        log.warn("kafka record handling failed, topic:{}, offset:{}", topic, record.offset(), exception);
                        consumer.seek(new org.apache.kafka.common.TopicPartition(record.topic(), record.partition()), record.offset());
                        failed = true;
                        break;
                    }
                }
                if (!failed) {
                    consumer.commitSync();
                }
            }
        } catch (WakeupException exception) {
            if (running.get()) {
                throw exception;
            }
        } finally {
            consumer.close();
        }
    }

    private static Properties producerProperties(String bootstrapServers) {
        Properties properties = new Properties();
        properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.setProperty(ProducerConfig.ACKS_CONFIG, "1");
        return properties;
    }

    private static Properties consumerProperties(String bootstrapServers, String group) {
        Properties properties = new Properties();
        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, group);
        properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return properties;
    }
}
