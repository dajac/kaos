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
package io.github.dajac.kaos.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import io.github.dajac.kaos.ConsumerHandle;
import io.github.dajac.kaos.ConsumerSettings;
import io.github.dajac.kaos.ConsumerSpec;
import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.Protocol;
import io.github.dajac.kaos.SimulatorSnapshot;
import io.github.dajac.kaos.TopicSpec;
import io.github.dajac.kaos.internal.proxy.NetworkFaults;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/** The consumers of a group, against mock Kafka consumers. */
class GroupRuntimeTest {

    private static final TopicPartition ORDERS_0 = new TopicPartition("orders", 0);

    /** A harness whose structural calls are not used: the test drives the group directly. */
    private static final class Harness implements GroupRuntime.Harness {
        @Override
        public void changed() {}

        @Override
        public void refuseBlocking() {}

        @Override
        public SimulatorSnapshot snapshot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SimulatorSnapshot awaitSnapshot(Predicate<SimulatorSnapshot> condition, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConsumerHandle addConsumer(GroupRuntime group, ConsumerSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeConsumer(GroupRuntime group, ConsumerRunner consumer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeGroup(GroupRuntime group) {
            throw new UnsupportedOperationException();
        }
    }

    private final Threads threads = new Threads();

    private MockConsumer<String, String> newClient(Map<String, Object> settings) {
        var client = new MockConsumer<String, String>("latest");
        client.updateEndOffsets(Map.of(ORDERS_0, 0L));
        client.schedulePollTask(() -> client.rebalance(List.of(ORDERS_0)));
        return client;
    }

    @Test
    void aClassicConsumerTakesTheSessionTimeoutAndHeartbeatIntervalOfTheGroup() {
        var classic = new ConsumerSettings(Protocol.CLASSIC, "", true, "", 0, 20, 300_000);
        var spec = new GroupSpec(
            "g",
            List.of("orders"),
            10_000,
            1_000,
            1000,
            classic,
            List.of(ConsumerSpec.inheriting(), ConsumerSpec.of(c -> c.protocol(Protocol.CONSUMER)))
        );
        var group = new GroupRuntime(spec, List.of(new TopicSpec("orders", 1)), new Harness());
        var settings = new CopyOnWriteArrayList<Map<String, Object>>();
        var consumers = new ConsumerRunner.Services(
            given -> {
                settings.add(given);
                return newClient(given);
            },
            threads,
            new NetworkFaults()
        );
        var resources = new Resources("test");

        group.runners().forEach(runner -> runner.start(consumers, resources));
        resources.close();

        assertThat(settings.get(0))
            .containsEntry(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10_000)
            .containsEntry(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1_000);
        // The consumer protocol client refuses both: they reach the broker as group configs.
        assertThat(settings.get(1))
            .doesNotContainKey(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG)
            .doesNotContainKey(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG);
    }
}
