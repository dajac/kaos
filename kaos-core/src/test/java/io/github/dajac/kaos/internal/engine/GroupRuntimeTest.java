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
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import io.github.dajac.kaos.ConsumerHandle;
import io.github.dajac.kaos.ConsumerSpec;
import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.SimulatorSnapshot;
import io.github.dajac.kaos.TopicSpec;
import io.github.dajac.kaos.internal.kafka.GroupCleanup;
import io.github.dajac.kaos.internal.kafka.GroupConfigManager;
import io.github.dajac.kaos.internal.proxy.NetworkFaults;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The start and the undo of a group, against mock Kafka consumers. */
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

    /** The group configs are the defaults, so nothing is read or written. */
    private static final class NoConfigs implements GroupConfigManager.Store {
        @Override
        public Map<String, String> read(String groupId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(String groupId, String key, Optional<String> value) {
            throw new UnsupportedOperationException();
        }
    }

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final Threads threads = new Threads();
    // The cleanup is best-effort: against no cluster, it times out quickly and logs.
    private final Admin admin = Admin.create(Map.of(
        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
        AdminClientConfig.CLIENT_ID_CONFIG, "kaos-test-admin"
    ));
    private GroupRuntime group;

    private MockConsumer<String, String> newClient(Map<String, Object> settings) {
        var clientId = (String) settings.get("client.id");
        var client = new MockConsumer<String, String>("latest") {
            @Override
            public void close(CloseOptions options) {
                events.add("close " + clientId + " with the autopilot " + (group.autopilot().enabled() ? "on" : "off"));
                super.close(options);
            }
        };
        client.updateEndOffsets(Map.of(ORDERS_0, 0L));
        client.schedulePollTask(() -> client.rebalance(List.of(ORDERS_0)));
        return client;
    }

    @AfterEach
    void close() {
        admin.close(Duration.ZERO);
    }

    @Test
    void theAutopilotIsOffBeforeAnyConsumerStopsAddedLaterIncluded() {
        var spec = new GroupSpec("g", List.of("orders"), 45000, 5000, 1000, null, List.of(ConsumerSpec.inheriting()));
        group = new GroupRuntime(spec, List.of(new TopicSpec("orders", 1)), new Harness());
        var services = new GroupRuntime.Services(
            new ConsumerRunner.Services(this::newClient, threads, new NetworkFaults()),
            new GroupConfigManager(new NoConfigs()),
            new GroupCleanup(admin, Duration.ofMillis(100))
        );
        var resources = new Resources("test");
        group.start(services, resources.child("group g"));
        // Long intervals: the autopilot is on, and flips nothing during the test.
        group.autopilot().start(Duration.ofHours(1), Duration.ofHours(1));

        var late = group.newConsumer(ConsumerSpec.inheriting());
        group.startConsumer(late);
        group.commitConsumer(late);

        resources.close();

        assertThat(events).containsExactlyInAnyOrder(
            "close g-consumer-0 with the autopilot off",
            "close g-consumer-1 with the autopilot off"
        );
        assertThat(group.autopilot().enabled()).isFalse();
    }
}
