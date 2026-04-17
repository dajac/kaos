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
    private final AtomicInteger generationId = new AtomicInteger(-1);
    private final Set<TopicPartition> localAssignment = ConcurrentHashMap.newKeySet();
    private volatile String protocol; // "consumer" or "classic"
    private volatile int pollTimeoutMs;
    private volatile int pollDelayMs;
    private volatile KafkaConsumer<String, String> consumer;
    private volatile Thread thread;
    private volatile boolean alive; // true while the consumer loop should run

    public ConsumerSlot(int index, String protocol, int pollTimeoutMs, int pollDelayMs) {
        this.index = index;
        this.protocol = protocol;
        this.pollTimeoutMs = pollTimeoutMs;
        this.pollDelayMs = pollDelayMs;
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

    public AtomicInteger generationId() {
        return generationId;
    }

    public Set<TopicPartition> localAssignment() {
        return localAssignment;
    }

    public String protocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public int pollTimeoutMs() {
        return pollTimeoutMs;
    }

    public int pollDelayMs() {
        return pollDelayMs;
    }

    public boolean alive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
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

    public void reset() {
        localAssignment.clear();
        generationId.set(-1);
        blocked.set(false);
        partitioned.set(false);
    }
}
