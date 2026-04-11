package io.github.dajac.kaos;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ConsumerSlot {
    private final int index;
    private final AtomicBoolean desiredSubscribed = new AtomicBoolean(true);
    private final AtomicBoolean blocked = new AtomicBoolean(false);
    private final AtomicBoolean partitioned = new AtomicBoolean(false);
    private final AtomicInteger processingDelayMs;
    private final AtomicInteger generationId = new AtomicInteger(-1);
    private final Set<TopicPartition> localAssignment = ConcurrentHashMap.newKeySet();
    private KafkaConsumer<String, String> consumer;
    private Thread thread;

    public ConsumerSlot(int index, int defaultProcessingDelayMs) {
        this.index = index;
        this.processingDelayMs = new AtomicInteger(defaultProcessingDelayMs);
    }

    public int index() {
        return index;
    }

    public String clientId() {
        return "consumer-" + index;
    }

    public AtomicBoolean desiredSubscribed() {
        return desiredSubscribed;
    }

    public AtomicBoolean blocked() {
        return blocked;
    }

    public AtomicBoolean partitioned() {
        return partitioned;
    }

    public AtomicInteger processingDelayMs() {
        return processingDelayMs;
    }

    public AtomicInteger generationId() {
        return generationId;
    }

    public Set<TopicPartition> localAssignment() {
        return localAssignment;
    }

    public KafkaConsumer<String, String> consumer() {
        return consumer;
    }

    public void setConsumer(KafkaConsumer<String, String> consumer) {
        this.consumer = consumer;
    }

    public Thread thread() {
        return thread;
    }

    public void setThread(Thread thread) {
        this.thread = thread;
    }
}
