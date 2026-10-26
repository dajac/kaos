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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class SnapshotRecordsTest {

    @Test
    void partitionRefsOrderByTopicThenPartition() {
        var sorted = Stream.of(new PartitionRef("t", 10), new PartitionRef("t", 2), new PartitionRef("a", 5))
            .sorted()
            .toList();

        assertThat(sorted)
            .containsExactly(new PartitionRef("a", 5), new PartitionRef("t", 2), new PartitionRef("t", 10));
        assertThat(new PartitionRef("my-topic", 3)).hasToString("my-topic-3");
    }

    @Test
    void theSimulatorSnapshotCopiesItsLists() {
        var topics = new ArrayList<>(List.of(new TopicSpec("t", 3)));
        var producers = new ArrayList<>(List.of(producer()));
        var groups = new ArrayList<>(List.of(group(Map.of(), List.of())));
        var snapshot = new SimulatorSnapshot(1, Phase.RUNNING, false, "", topics, producers, groups);

        topics.clear();
        producers.clear();
        groups.clear();

        assertThat(snapshot.topics()).hasSize(1);
        assertThat(snapshot.producers()).hasSize(1);
        assertThat(snapshot.groups()).hasSize(1);
        assertThatThrownBy(() -> snapshot.groups().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void withRevisionChangesOnlyTheRevision() {
        var snapshot = new SimulatorSnapshot(1, Phase.IDLE, true, "boom", List.of(), List.of(producer()), List.of());

        assertThat(snapshot.withRevision(2))
            .isEqualTo(new SimulatorSnapshot(2, Phase.IDLE, true, "boom", List.of(), List.of(producer()), List.of()));
        assertThat(snapshot.withRevision(1)).isEqualTo(snapshot);
    }

    @Test
    void theProducerSnapshotCopiesItsTopics() {
        var topics = new ArrayList<>(List.of("t"));
        var producer = new ProducerSnapshot("producer-0", topics, ProducerState.PAUSED, 2.0);

        topics.clear();

        assertThat(producer.topics()).containsExactly("t");
    }

    @Test
    void theGroupSnapshotCopiesItsCollectionsAndKeepsTheLagOrder() {
        var values = new ArrayList<>(List.of(1L, 2L));
        var lag = new LinkedHashMap<String, List<Long>>();
        lag.put("t", values);
        lag.put("a", List.of(3L));
        var consumers = new ArrayList<>(List.of(consumer(List.of(), List.of())));
        var group = group(lag, consumers);

        values.clear();
        lag.clear();
        consumers.clear();

        assertThat(group.lag()).containsExactly(Map.entry("t", List.of(1L, 2L)), Map.entry("a", List.of(3L)));
        assertThat(group.consumers()).hasSize(1);
        assertThatThrownBy(() -> group.lag().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> group.lag().get("t").clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theConsumerSnapshotCopiesItsLists() {
        var faults = new ArrayList<>(List.of(Fault.freeze()));
        var partitions = new ArrayList<>(List.of(new PartitionView("t", 0, PartitionState.ASSIGNED)));
        var refs = new ArrayList<>(List.of(new PartitionRef("t", 0)));
        var consumer = new ConsumerSnapshot(
            "consumer-0",
            "g-consumer-0",
            Protocol.CONSUMER,
            true,
            faults,
            null,
            null,
            -1,
            -1,
            ConsumerStatus.JOINING,
            ConsumerStatus.FROZEN,
            null,
            partitions,
            new ConsumerSnapshot.Raw(refs, refs, refs)
        );

        faults.clear();
        partitions.clear();
        refs.clear();

        assertThat(consumer.faults()).containsExactly(Fault.freeze());
        assertThat(consumer.partitions()).hasSize(1);
        assertThat(consumer.raw().assignment()).hasSize(1);
        assertThat(consumer.raw().targetAssignment()).hasSize(1);
        assertThat(consumer.raw().localAssignment()).hasSize(1);
        assertThat(consumer.memberId()).isEmpty();
        assertThat(consumer.instanceId()).isEmpty();
        assertThat(consumer.failure()).isEmpty();
    }

    private static ProducerSnapshot producer() {
        return new ProducerSnapshot("producer-0", List.of("t"), ProducerState.RUNNING, 2.0);
    }

    private static GroupSnapshot group(Map<String, List<Long>> lag, List<ConsumerSnapshot> consumers) {
        return new GroupSnapshot(
            "g",
            GroupType.CONSUMER,
            GroupState.STABLE,
            3,
            3,
            -1,
            List.of("t", "a"),
            lag,
            consumers
        );
    }

    private static ConsumerSnapshot consumer(List<Fault> faults, List<PartitionView> partitions) {
        return new ConsumerSnapshot(
            "consumer-0",
            "g-consumer-0",
            Protocol.CONSUMER,
            true,
            faults,
            "member-0",
            "",
            3,
            -1,
            ConsumerStatus.STABLE,
            ConsumerStatus.STABLE,
            "",
            partitions,
            new ConsumerSnapshot.Raw(List.of(), List.of(), List.of())
        );
    }
}
