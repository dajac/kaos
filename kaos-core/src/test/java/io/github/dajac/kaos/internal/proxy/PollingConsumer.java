/*
 * Copyright 2026 The Kaos Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dajac.kaos.internal.proxy;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * A classic-protocol consumer that polls on its own thread, for the proxy integration tests. It records the
 * values it consumes and exposes the connection metrics of the client.
 *
 * <p>The classic protocol is used because its session timeout is a client config that goes down
 * to 6 s on a default broker, which keeps the eviction tests short.
 */
final class PollingConsumer implements AutoCloseable {

    static final Duration SESSION_TIMEOUT = Duration.ofSeconds(6);

    private final KafkaConsumer<String, String> consumer;
    private final Set<String> values = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread thread;

    /**
     * @param requestTimeout the client's {@code request.timeout.ms}, which is how long the client
     *     waits on a request that the proxy dropped
     */
    PollingConsumer(String bootstrapServers, String groupId, String clientId, String topic, Duration requestTimeout) {
        var config = new HashMap<String, Object>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        config.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "classic");
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, (int) SESSION_TIMEOUT.toMillis());
        config.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1000);
        config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) requestTimeout.toMillis());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        this.consumer = new KafkaConsumer<>(config);
        this.thread = new Thread(() -> run(topic), "test-" + clientId);
        this.thread.start();
    }

    private void run(String topic) {
        try {
            consumer.subscribe(List.of(topic));
            while (running.get()) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> values.add(record.value()));
            }
        } catch (WakeupException e) {
            // close() interrupts a blocked poll this way.
        } finally {
            consumer.close(Duration.ofSeconds(5));
        }
    }

    /** The values consumed so far. */
    Set<String> values() {
        return values;
    }

    /** The number of connections the client has seen closed, across all its connections. */
    double closedConnections() {
        return consumer.metrics().values().stream()
            .filter(metric -> metric.metricName().name().equals("connection-close-total"))
            .filter(metric -> metric.metricName().group().equals("consumer-metrics"))
            .map(Metric::metricValue)
            .mapToDouble(value -> ((Number) value).doubleValue())
            .sum();
    }

    @Override
    public void close() throws InterruptedException {
        running.set(false);
        consumer.wakeup();
        thread.join(Duration.ofSeconds(30).toMillis());
    }
}
