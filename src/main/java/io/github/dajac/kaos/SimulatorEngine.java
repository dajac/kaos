package io.github.dajac.kaos;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class SimulatorEngine {

    public enum Phase { IDLE, STARTING, RUNNING, STOPPING }

    private final String bootstrapServers;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.IDLE);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private static final int PROXY_BASE_PORT = 19092;

    private SimulatorConfig config;
    private final List<ConsumerSlot> slots = new ArrayList<>();
    private KafkaProducer<String, String> producer;
    private Thread producerThread;
    private AdminPoller adminPoller;
    private KafkaProxyManager proxyManager;

    // Chaos loop.
    private Thread chaosThread;
    private final AtomicBoolean chaosRunning = new AtomicBoolean(false);

    public SimulatorEngine(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public Phase phase() {
        return phase.get();
    }

    public SimulatorConfig config() {
        return config;
    }

    public List<ConsumerSlot> slots() {
        return Collections.unmodifiableList(slots);
    }

    public AdminPoller adminPoller() {
        return adminPoller;
    }

    public boolean isChaosRunning() {
        return chaosRunning.get();
    }

    public synchronized void start(SimulatorConfig config) throws Exception {
        if (!phase.compareAndSet(Phase.IDLE, Phase.STARTING)) {
            throw new IllegalStateException("Simulator is not idle, current phase: " + phase.get());
        }

        try {
            this.config = config;
            running.set(true);

            createTopics();
            configureGroup();
            startProxy();
            startProducer();
            startConsumers();

            adminPoller = new AdminPoller(bootstrapServers, config.groupId);
            adminPoller.start();

            phase.set(Phase.RUNNING);
        } catch (Exception e) {
            running.set(false);
            phase.set(Phase.IDLE);
            throw e;
        }
    }

    public synchronized void stop() {
        if (!phase.compareAndSet(Phase.RUNNING, Phase.STOPPING)) {
            return;
        }

        stopChaos();
        running.set(false);

        // Wait for consumer threads to exit.
        for (ConsumerSlot slot : slots) {
            try {
                slot.thread().join();
            } catch (InterruptedException e) {
                // Ignore.
            }
        }

        // Close consumers.
        for (ConsumerSlot slot : slots) {
            try {
                slot.consumer().close();
            } catch (Exception e) {
                // Ignore.
            }
        }
        slots.clear();

        // Stop producer.
        if (producerThread != null) {
            try {
                producerThread.join(5000);
            } catch (InterruptedException e) {
                // Ignore.
            }
        }
        if (producer != null) {
            producer.close();
            producer = null;
        }

        // Stop admin poller.
        if (adminPoller != null) {
            adminPoller.stop();
            adminPoller = null;
        }

        // Stop proxy.
        if (proxyManager != null) {
            proxyManager.stop();
            proxyManager = null;
        }

        phase.set(Phase.IDLE);
    }

    public void startChaos(int intervalMinMs, int intervalMaxMs) {
        if (!chaosRunning.compareAndSet(false, true)) {
            return;
        }
        chaosThread = new Thread(() -> {
            while (chaosRunning.get() && running.get()) {
                int sleepMs = ThreadLocalRandom.current().nextInt(intervalMinMs, intervalMaxMs + 1);
                long deadline = System.currentTimeMillis() + sleepMs;
                while (chaosRunning.get() && running.get() && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(Math.min(500, deadline - System.currentTimeMillis()));
                    } catch (InterruptedException e) {
                        break;
                    }
                }

                if (!chaosRunning.get() || !running.get()) break;

                int victim = ThreadLocalRandom.current().nextInt(slots.size());
                ConsumerSlot slot = slots.get(victim);
                boolean current = slot.desiredSubscribed().get();
                slot.desiredSubscribed().set(!current);
            }
        }, "chaos-loop");
        chaosThread.setDaemon(true);
        chaosThread.start();
    }

    public void stopChaos() {
        chaosRunning.set(false);
        if (chaosThread != null) {
            try {
                chaosThread.join(2000);
            } catch (InterruptedException e) {
                // Ignore.
            }
            chaosThread = null;
        }
    }

    private void startProxy() throws Exception {
        proxyManager = new KafkaProxyManager(bootstrapServers, PROXY_BASE_PORT);
        proxyManager.start();
    }

    private void createTopics() throws Exception {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        try (AdminClient admin = AdminClient.create(props)) {
            for (String topic : config.topicNames()) {
                try {
                    admin.createTopics(Collections.singleton(new NewTopic(topic, config.numPartitions, (short) 1))).all().get();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof TopicExistsException) {
                        // Topic already exists, ignore.
                    } else {
                        throw e;
                    }
                }
            }
        }
    }

    private void configureGroup() throws Exception {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        try (AdminClient admin = AdminClient.create(props)) {
            ConfigResource groupResource = new ConfigResource(ConfigResource.Type.GROUP, config.groupId);

            Collection<AlterConfigOp> ops = Arrays.asList(
                new AlterConfigOp(
                    new org.apache.kafka.clients.admin.ConfigEntry(
                        "consumer.session.timeout.ms",
                        String.valueOf(config.sessionTimeoutMs)),
                    AlterConfigOp.OpType.SET),
                new AlterConfigOp(
                    new org.apache.kafka.clients.admin.ConfigEntry(
                        "consumer.heartbeat.interval.ms",
                        String.valueOf(config.heartbeatIntervalMs)),
                    AlterConfigOp.OpType.SET),
                new AlterConfigOp(
                    new org.apache.kafka.clients.admin.ConfigEntry(
                        "consumer.assignment.interval.ms",
                        String.valueOf(config.assignmentIntervalMs)),
                    AlterConfigOp.OpType.SET)
            );

            Map<ConfigResource, Collection<AlterConfigOp>> configs = new HashMap<>();
            configs.put(groupResource, ops);
            admin.incrementalAlterConfigs(configs).all().get();
        }
    }

    private void startProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        producer = new KafkaProducer<>(props);
        List<String> topics = config.topicNames();
        producerThread = new Thread(() -> {
            long seq = 0;
            while (running.get()) {
                String topic = topics.get((int) (seq % topics.size()));
                producer.send(new ProducerRecord<>(topic, "key-" + (seq % config.numPartitions), "value-" + seq));
                seq++;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "producer");
        producerThread.setDaemon(true);
        producerThread.start();
    }

    private void startConsumers() {
        List<String> topics = config.topicNames();
        for (int i = 0; i < config.numConsumers; i++) {
            ConsumerSlot slot = new ConsumerSlot(i, config.defaultProcessingDelayMs);

            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, proxyManager.proxyBootstrapServers());
            props.put(ConsumerConfig.GROUP_ID_CONFIG, config.groupId);
            props.put(ConsumerConfig.CLIENT_ID_CONFIG, slot.clientId());
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "consumer");
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
            props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, String.valueOf(config.maxPollIntervalMs));
            if (config.assignor != null && !config.assignor.isEmpty()) {
                props.put(ConsumerConfig.GROUP_REMOTE_ASSIGNOR_CONFIG, config.assignor);
            }
            if (config.staticMembers) {
                props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, config.groupId + "-" + slot.clientId());
            }

            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
            slot.setConsumer(consumer);

            Thread thread = new Thread(() -> consumerLoop(slot, topics), slot.clientId());
            slot.setThread(thread);
            thread.start();

            slots.add(slot);
        }
    }

    private void consumerLoop(ConsumerSlot slot, List<String> topics) {
        KafkaConsumer<String, String> consumer = slot.consumer();
        boolean currentlySubscribed = false;

        while (running.get()) {
            boolean desired = slot.desiredSubscribed().get();
            if (desired && !currentlySubscribed) {
                consumer.subscribe(topics);
                currentlySubscribed = true;
            } else if (!desired && currentlySubscribed) {
                try {
                    consumer.unsubscribe();
                    currentlySubscribed = false;
                } catch (Exception e) {
                    // May fail if called mid-reconciliation. Retry next iteration.
                }
            }

            if (slot.blocked().get()) {
                // Blocked — skip poll() so the member stays in the group
                // (heartbeats continue) but doesn't process rebalance callbacks.
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }

            try {
                consumer.poll(Duration.ofMillis(500));
            } catch (Exception e) {
                if (running.get()) {
                    // Ignore transient errors.
                }
            }

            // Processing delay — simulates slow record processing.
            // Re-read the delay each sleep iteration so changes apply immediately.
            long delayStart = System.currentTimeMillis();
            while (running.get()) {
                int delayMs = slot.processingDelayMs().get();
                long elapsed = System.currentTimeMillis() - delayStart;
                if (elapsed >= delayMs) break;
                try {
                    Thread.sleep(Math.min(500, Math.max(1, delayMs - elapsed)));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }
}
