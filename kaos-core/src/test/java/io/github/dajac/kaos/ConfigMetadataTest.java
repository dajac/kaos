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
package io.github.dajac.kaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import io.github.dajac.kaos.ConfigMetadata.Range;
import io.github.dajac.kaos.ConfigMetadata.Scope;
import io.github.dajac.kaos.ConfigMetadata.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigMetadataTest {

    /** The expected table: scope, key, type, default, and the range or null. */
    static Stream<Arguments> fields() {
        return Stream.of(
            Arguments.of(Scope.SIMULATOR, "numTopics", Type.INT, 1, new Range(1, 20)),
            Arguments.of(Scope.TOPIC, "partitions", Type.INT, 36, new Range(1, 100)),
            Arguments.of(Scope.PRODUCER, "ratePerPartition", Type.DOUBLE, 2.0, null),
            Arguments.of(Scope.GROUP, "id", Type.STRING, "kaos", null),
            Arguments.of(Scope.GROUP, "numConsumers", Type.INT, 4, null),
            Arguments.of(Scope.GROUP, "sessionTimeoutMs", Type.INT, 45_000, new Range(1_000, 300_000)),
            Arguments.of(Scope.GROUP, "heartbeatIntervalMs", Type.INT, 5_000, new Range(500, 60_000)),
            Arguments.of(Scope.GROUP, "assignmentIntervalMs", Type.INT, 1_000, new Range(0, 60_000)),
            Arguments.of(Scope.CONSUMER, "protocol", Type.PROTOCOL, Protocol.CONSUMER, null),
            Arguments.of(Scope.CONSUMER, "assignor", Type.STRING, "", null),
            Arguments.of(Scope.CONSUMER, "staticMember", Type.BOOLEAN, false, null),
            Arguments.of(Scope.CONSUMER, "rackId", Type.STRING, "", null),
            Arguments.of(Scope.CONSUMER, "pollTimeoutMs", Type.INT, 0, new Range(0, 60_000)),
            Arguments.of(Scope.CONSUMER, "pollDelayMs", Type.INT, 1_000, new Range(0, 120_000)),
            Arguments.of(Scope.CONSUMER, "maxPollIntervalMs", Type.INT, 300_000, new Range(1_000, 600_000))
        );
    }

    @ParameterizedTest
    @MethodSource("fields")
    void encodesTheDefaultAndTheRangeOfEachField(
        Scope scope, String key, Type type, Object defaultValue, Range range
    ) {
        var field = ConfigMetadata.get().field(scope, key).orElseThrow();

        assertThat(field.type()).isEqualTo(type);
        assertThat(field.defaultValue()).isEqualTo(defaultValue);
        assertThat(field.range()).isEqualTo(Optional.ofNullable(range));
        assertThat(field.label()).isNotBlank();
    }

    @Test
    void listsExactlyTheExpectedFields() {
        assertThat(ConfigMetadata.get().fields()).hasSize((int) fields().count());
    }

    @Test
    void keysAreUniqueWithinAScope() {
        var seen = new HashSet<String>();
        for (var field : ConfigMetadata.get().fields()) {
            assertThat(seen.add(field.scope() + "." + field.key())).as(field.key()).isTrue();
        }
    }

    @Test
    void everyDefaultIsWithinItsRange() {
        for (var field : ConfigMetadata.get().fields()) {
            field.range().ifPresent(
                range -> assertThat(range.contains(field.intDefault())).as(field.key()).isTrue()
            );
        }
    }

    @Test
    void unknownFieldIsEmpty() {
        assertThat(ConfigMetadata.get().field(Scope.TOPIC, "sessionTimeoutMs")).isEmpty();
    }

    @Test
    void listsTheAssignorsOfEachProtocol() {
        var metadata = ConfigMetadata.get();

        assertThat(metadata.assignors()).containsOnlyKeys(Protocol.CONSUMER, Protocol.CLASSIC);
        assertThat(metadata.assignors(Protocol.CONSUMER).names()).containsExactly("uniform", "range");
        assertThat(metadata.assignors(Protocol.CONSUMER).customAllowed()).isTrue();
        assertThat(metadata.assignors(Protocol.CLASSIC).names())
            .containsExactly("range", "roundrobin", "sticky", "cooperative-sticky");
        assertThat(metadata.assignors(Protocol.CLASSIC).customAllowed()).isFalse();
    }

    @Test
    void classicAcceptsOnlyItsNamesAndTheEmptyName() {
        var classic = ConfigMetadata.get().assignors(Protocol.CLASSIC);

        assertThat(classic.accepts("sticky")).isTrue();
        assertThat(classic.accepts("")).isTrue();
        assertThat(classic.accepts("uniform")).isFalse();
    }

    @Test
    void consumerAcceptsAnyName() {
        assertThat(ConfigMetadata.get().assignors(Protocol.CONSUMER).accepts("my-custom-assignor")).isTrue();
    }

    @Test
    void nameRuleIsTheLegalTopicNameRule() {
        var rule = ConfigMetadata.get().nameRule();

        assertThat(rule).isEqualTo(new ConfigMetadata.NameRule("[a-zA-Z0-9._-]+", 249));
        assertThat(rule.accepts("Az09._-")).isTrue();
        assertThat(rule.accepts("a".repeat(249))).isTrue();
        assertThat(rule.accepts("a".repeat(250))).isFalse();
        assertThat(List.of("", ".", "..", "a b", "a/b", "é").stream().map(rule::accepts)).containsOnly(false);
    }

    @Test
    void listsTheFaults() {
        assertThat(ConfigMetadata.get().faults())
            .containsExactly("freeze", "network-isolation", "connection-reset");
    }

    @Test
    void isImmutable() {
        var metadata = ConfigMetadata.get();

        assertThatThrownBy(() -> metadata.fields().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> metadata.assignors().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> metadata.faults().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> metadata.assignors(Protocol.CLASSIC).names().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rangeIsInclusive() {
        var range = new Range(1, 3);

        assertThat(List.of(0, 1, 3, 4).stream().map(range::contains)).containsExactly(false, true, true, false);
        assertThatThrownBy(() -> new Range(2, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consumerSettingsDefaultsComeFromTheTable() {
        assertThat(ConsumerSettings.defaults())
            .isEqualTo(new ConsumerSettings(Protocol.CONSUMER, "", false, "", 0, 1_000, 300_000));
    }
}
