package io.github.dajac.kaos.internal.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.SimulationException;
import io.github.dajac.kaos.internal.kafka.GroupConfigManager.RefusedConfigException;
import org.junit.jupiter.api.Test;

class GroupConfigManagerTest {

    /** An in-memory store that refuses the keys it is told to and records the writes. */
    private static final class FakeStore implements GroupConfigManager.Store {
        private final Map<String, String> configs = new HashMap<>();
        private final Set<String> refused = new java.util.HashSet<>();
        private final List<String> writes = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public Map<String, String> read(String groupId) {
            return Map.copyOf(configs);
        }

        @Override
        public void write(String groupId, String key, Optional<String> value) {
            writes.add(key + "=" + value.orElse("<deleted>"));
            if (refused.contains(key)) {
                throw new RefusedConfigException("unknown " + key, null);
            }
            if (failure != null) {
                throw failure;
            }
            value.ifPresentOrElse(set -> configs.put(key, set), () -> configs.remove(key));
        }
    }

    private final FakeStore store = new FakeStore();
    private final GroupConfigManager manager = new GroupConfigManager(store);

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
        assertThat(applied.warnings()).isEmpty();
        assertThat(store.writes).isEmpty();
    }

    @Test
    void appliesTheExplicitKeysAndRevertsThemToWhatTheyWere() {
        store.configs.put("consumer.session.timeout.ms", "20000");
        var spec = GroupSpec.of("g", g -> g
            .subscribe("t")
            .sessionTimeout(Duration.ofSeconds(10))
            .heartbeatInterval(Duration.ofSeconds(1))
        );

        var applied = manager.apply(spec);

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
            "consumer.session.timeout.ms=10000",
            "consumer.heartbeat.interval.ms=1000",
            "consumer.session.timeout.ms=20000",
            "consumer.heartbeat.interval.ms=<deleted>"
        );
    }

    @Test
    void aRefusedKeyIsAWarningAndTheOthersApply() {
        store.refused.add("consumer.assignment.interval.ms");
        var spec = GroupSpec.of("g", g -> g
            .subscribe("t")
            .sessionTimeout(Duration.ofSeconds(10))
            .assignmentInterval(Duration.ofSeconds(2))
        );

        var applied = manager.apply(spec);

        assertThat(store.configs).containsOnly(Map.entry("consumer.session.timeout.ms", "10000"));
        assertThat(applied.previous()).containsOnlyKeys("consumer.session.timeout.ms");
        assertThat(applied.warnings()).singleElement().asString()
            .contains("consumer.assignment.interval.ms=2000")
            .contains("unknown consumer.assignment.interval.ms");

        manager.revert("g", applied.previous());

        assertThat(store.configs).isEmpty();
    }

    @Test
    void anotherFailurePutsBackWhatWasSetAndIsThrown() {
        var spec = GroupSpec.of("g", g -> g
            .subscribe("t")
            .sessionTimeout(Duration.ofSeconds(10))
            .heartbeatInterval(Duration.ofSeconds(1))
        );
        store.failure = null;
        var failing = new GroupConfigManager(new GroupConfigManager.Store() {
            @Override
            public Map<String, String> read(String groupId) {
                return store.read(groupId);
            }

            @Override
            public void write(String groupId, String key, Optional<String> value) {
                if (key.equals("consumer.heartbeat.interval.ms") && value.isPresent()) {
                    throw new SimulationException("broker down");
                }
                store.write(groupId, key, value);
            }
        });

        assertThatThrownBy(() -> failing.apply(spec)).isInstanceOf(SimulationException.class).hasMessage("broker down");

        assertThat(store.configs).isEmpty();
    }

    @Test
    void aRevertTriesEveryKeyAndReportsTheFailures() {
        store.refused.add("consumer.session.timeout.ms");
        var previous = new java.util.LinkedHashMap<String, Optional<String>>();
        previous.put("consumer.session.timeout.ms", Optional.empty());
        previous.put("consumer.heartbeat.interval.ms", Optional.of("3000"));

        assertThatThrownBy(() -> manager.revert("g", previous))
            .isInstanceOf(SimulationException.class)
            .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));

        assertThat(store.configs).containsOnly(Map.entry("consumer.heartbeat.interval.ms", "3000"));
    }
}
