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
package io.github.dajac.kaos.internal.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.SimulationException;
import io.github.dajac.kaos.internal.kafka.GroupConfigManager.RefusedConfigException;
import org.junit.jupiter.api.Test;

class GroupConfigManagerTest {

    /**
     * An in-memory store that records each alter. An alter with a refused key sets nothing; the
     * queued failures are thrown, one per alter, after the alter is applied.
     */
    private static final class FakeStore implements GroupConfigManager.Store {
        private final Map<String, String> configs = new HashMap<>();
        private final Set<String> refused = new HashSet<>();
        private final Queue<RuntimeException> failures = new ArrayDeque<>();
        private final List<String> writes = new ArrayList<>();

        @Override
        public Map<String, String> read(String groupId) {
            return Map.copyOf(configs);
        }

        @Override
        public void write(String groupId, Map<String, Optional<String>> changes) {
            var parts = new ArrayList<String>();
            changes.forEach((key, value) -> parts.add(key + "=" + value.orElse("<deleted>")));
            writes.add(String.join(", ", parts));
            for (var key : changes.keySet()) {
                if (refused.contains(key)) {
                    throw new RefusedConfigException("unknown " + key, null);
                }
            }
            changes.forEach(
                (key, value) -> value.ifPresentOrElse(set -> configs.put(key, set), () -> configs.remove(key)));
            var failure = failures.poll();
            if (failure != null) {
                throw failure;
            }
        }
    }

    private final FakeStore store = new FakeStore();
    private final GroupConfigManager manager = new GroupConfigManager(store);

    private static GroupSpec sessionAndHeartbeat() {
        return GroupSpec.of("g", g -> g
            .subscribe("t")
            .sessionTimeout(Duration.ofSeconds(10))
            .heartbeatInterval(Duration.ofSeconds(1))
        );
    }

    @Test
    void onlyTheSettingsThatAreNotTheDefaultAreSent() {
        assertThat(GroupConfigManager.explicitConfigs(GroupSpec.of("g", g -> g.subscribe("t")))).isEmpty();

        var spec = GroupSpec.of("g", g -> g
            .subscribe("t")
            .sessionTimeout(Duration.ofSeconds(10))
            .assignmentInterval(Duration.ofSeconds(2))
        );

        assertThat(GroupConfigManager.explicitConfigs(spec)).containsExactly(
            Map.entry("consumer.session.timeout.ms", "10000"),
            Map.entry("consumer.assignment.interval.ms", "2000")
        );
    }

    @Test
    void appliesNothingForTheDefaults() {
        var applied = manager.apply(GroupSpec.of("g", g -> g.subscribe("t")));

        assertThat(applied.previous()).isEmpty();
        assertThat(store.writes).isEmpty();
    }

    @Test
    void appliesTheExplicitKeysInOneAlterAndRevertsThemInOne() {
        store.configs.put("consumer.session.timeout.ms", "20000");

        var applied = manager.apply(sessionAndHeartbeat());

        assertThat(store.configs).containsOnly(
            Map.entry("consumer.session.timeout.ms", "10000"),
            Map.entry("consumer.heartbeat.interval.ms", "1000")
        );
        assertThat(applied.previous()).containsExactly(
            Map.entry("consumer.session.timeout.ms", Optional.of("20000")),
            Map.entry("consumer.heartbeat.interval.ms", Optional.empty())
        );

        manager.revert("g", applied.previous());

        assertThat(store.configs).containsOnly(Map.entry("consumer.session.timeout.ms", "20000"));
        assertThat(store.writes).containsExactly(
            "consumer.session.timeout.ms=10000, consumer.heartbeat.interval.ms=1000",
            "consumer.session.timeout.ms=20000, consumer.heartbeat.interval.ms=<deleted>"
        );
    }

    @Test
    void aRefusedAlterIsThrownWithEveryKeyAndSetsNothing() {
        store.configs.put("consumer.session.timeout.ms", "20000");
        store.refused.add("consumer.heartbeat.interval.ms");

        assertThatThrownBy(() -> manager.apply(sessionAndHeartbeat()))
            .isInstanceOf(SimulationException.class)
            .hasMessage(
                "The broker refused the group configs consumer.session.timeout.ms=10000, "
                    + "consumer.heartbeat.interval.ms=1000 of group g: unknown consumer.heartbeat.interval.ms"
            )
            .hasCauseInstanceOf(RefusedConfigException.class);

        assertThat(store.configs).containsOnly(Map.entry("consumer.session.timeout.ms", "20000"));
        assertThat(store.writes)
            .containsExactly("consumer.session.timeout.ms=10000, consumer.heartbeat.interval.ms=1000");
    }

    @Test
    void aRefusedSingleKeyIsNamedAlone() {
        store.refused.add("consumer.assignment.interval.ms");
        var spec = GroupSpec.of("g", g -> g.subscribe("t").assignmentInterval(Duration.ofSeconds(2)));

        assertThatThrownBy(() -> manager.apply(spec))
            .isInstanceOf(SimulationException.class)
            .hasMessage(
                "The broker refused the group config consumer.assignment.interval.ms=2000 of group g: "
                    + "unknown consumer.assignment.interval.ms"
            );
    }

    @Test
    void anotherFailurePutsTheKeysBackAndIsThrown() {
        store.failures.add(new SimulationException("timed out"));

        assertThatThrownBy(() -> manager.apply(sessionAndHeartbeat()))
            .isInstanceOf(SimulationException.class)
            .hasMessage("timed out")
            .satisfies(e -> assertThat(e.getSuppressed()).isEmpty());

        assertThat(store.configs).isEmpty();
        assertThat(store.writes).containsExactly(
            "consumer.session.timeout.ms=10000, consumer.heartbeat.interval.ms=1000",
            "consumer.session.timeout.ms=<deleted>, consumer.heartbeat.interval.ms=<deleted>"
        );
    }

    @Test
    void anotherFailureWhoseRevertFailsKeepsTheRevertFailureAsSuppressed() {
        store.failures.add(new SimulationException("timed out"));
        store.failures.add(new SimulationException("broker down"));

        assertThatThrownBy(() -> manager.apply(sessionAndHeartbeat()))
            .isInstanceOf(SimulationException.class)
            .hasMessage("timed out")
            .satisfies(e -> assertThat(e.getSuppressed()).singleElement()
                .satisfies(
                    revert -> assertThat(revert).hasMessage("Could not revert the configs of group g: broker down")));
    }

    @Test
    void aRevertThatFailsIsThrown() {
        store.refused.add("consumer.session.timeout.ms");
        var previous = new LinkedHashMap<String, Optional<String>>();
        previous.put("consumer.session.timeout.ms", Optional.empty());
        previous.put("consumer.heartbeat.interval.ms", Optional.of("3000"));

        assertThatThrownBy(() -> manager.revert("g", previous))
            .isInstanceOf(SimulationException.class)
            .hasMessage("Could not revert the configs of group g: unknown consumer.session.timeout.ms")
            .hasCauseInstanceOf(RefusedConfigException.class);
    }
}
