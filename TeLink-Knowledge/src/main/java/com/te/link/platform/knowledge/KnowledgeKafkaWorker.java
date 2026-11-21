package com.te.link.platform.knowledge;

import com.google.gson.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Kafka 至少一次投递 + MySQL 任务幂等/租约；终态写入后才提交该条 offset。 */
public class KnowledgeKafkaWorker implements AutoCloseable {
    private final RagRepository repository;private final RagEngine engine;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ScheduledExecutorService dispatcher=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"knowledge-outbox");t.setDaemon(true);return t;});
    private volatile KafkaConsumer<String,String> consumer;
    private KafkaProducer<String,String> producer;private Thread thread;
    public KnowledgeKafkaWorker(RagRepository repository,RagEngine engine){this.repository=repository;this.engine=engine;}
    public void start(){
        if(!running.compareAndSet(false,true))return;
        Properties p=RagConfig.kafkaProperties();p.put("key.serializer","org.apache.kafka.common.serialization.StringSerializer");p.put("value.serializer","org.apache.kafka.common.serialization.StringSerializer");p.put("acks","all");p.put("enable.idempotence","true");p.put("max.block.ms","10000");p.put("request.timeout.ms","5000");p.put("delivery.timeout.ms","15000");producer=new KafkaProducer<>(p);
        dispatcher.scheduleWithFixedDelay(()->{try{repository.recover();for(int i=0;i<10 && running.get();i++)if(!repository.deliverOne((topic,key,payload)->{JsonObject event=JsonParser.parseString(payload).getAsJsonObject();String document=RagRepository.text(event,"documentId");producer.send(new ProducerRecord<>(topic,document.isBlank()?key:document,payload)).get(20,TimeUnit.SECONDS);}))break;}catch(Exception e){System.err.println("[Knowledge] outbox pending: "+e.getClass().getSimpleName());}},0,2,TimeUnit.SECONDS);
        thread=new Thread(this::consume,"knowledge-index-consumer");thread.start();
    }
    private void consume(){
        while(running.get()){
            Properties p=RagConfig.kafkaProperties();p.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");p.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");p.put("group.id",RagConfig.GROUP);p.put("enable.auto.commit","false");p.put("auto.offset.reset","earliest");p.put("max.poll.records","1");p.put("max.poll.interval.ms","1800000");
            try(KafkaConsumer<String,String> c=new KafkaConsumer<>(p)){
                consumer=c;c.subscribe(List.of(RagConfig.TOPIC));
                while(running.get())for(ConsumerRecord<String,String> record:c.poll(Duration.ofMillis(500))){
                    try{
                        String taskId=null;
                        try{if(record.value()==null || record.value().length()>10000)throw new IllegalArgumentException();taskId=RagRepository.text(JsonParser.parseString(record.value()).getAsJsonObject(),"taskId");if(!taskId.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException();}
                        catch(RuntimeException malformed){JsonObject dead=new JsonObject();dead.addProperty("reason","invalid task envelope");dead.addProperty("sourcePartition",record.partition());dead.addProperty("sourceOffset",record.offset());producer.send(new ProducerRecord<>(RagConfig.DLQ_TOPIC,record.key(),dead.toString())).get(20,TimeUnit.SECONDS);}
                        if(taskId!=null && taskId.matches("[a-f0-9-]{36}"))engine.process(taskId);
                        c.commitSync(Map.of(new TopicPartition(record.topic(),record.partition()),new OffsetAndMetadata(record.offset()+1)));
                    }catch(Exception e){c.seek(new TopicPartition(record.topic(),record.partition()),record.offset());Thread.sleep(1000);}
                }
            }catch(WakeupException e){if(!running.get())break;}
            catch(Exception e){System.err.println("[Knowledge] consumer retry: "+e.getClass().getSimpleName());if(running.get())try{Thread.sleep(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}}
            finally{consumer=null;}
        }
    }
    @Override public void close(){running.set(false);var c=consumer;if(c!=null)c.wakeup();dispatcher.shutdownNow();if(thread!=null){thread.interrupt();try{thread.join(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}if(producer!=null)producer.close(Duration.ofSeconds(5));}
}
