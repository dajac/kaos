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
package io.github.dajac.kaos.internal.state;

import static io.github.dajac.kaos.internal.state.StatusDeriverTest.refs;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.dajac.kaos.AutopilotSnapshot;
import io.github.dajac.kaos.ConsumerSnapshot;
import io.github.dajac.kaos.ConsumerStatus;
import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.GroupState;
import io.github.dajac.kaos.GroupType;
import io.github.dajac.kaos.PartitionRef;
import io.github.dajac.kaos.PartitionState;
import io.github.dajac.kaos.PartitionView;
import io.github.dajac.kaos.Protocol;
import io.github.dajac.kaos.TopicSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SnapshotAssemblerTest {
    private static final List<TopicSpec> TOPICS = List.of(new TopicSpec("t", 3), new TopicSpec("a", 2));
    private static final AutopilotSnapshot AUTOPILOT_OFF = new AutopilotSnapshot(false, 10_000, 30_000);
    private static final AutopilotSnapshot AUTOPILOT_ON = new AutopilotSnapshot(true, 5_000, 20_000);

    @Test
    void mergesAConsumerGroup() {
        var group = new GroupLocalState(
            "g",
            TOPICS,
            AUTOPILOT_ON,
            List.of(
                consumer(0, Protocol.CONSUMER, "uniform", true, Set.of(), 4, "t-0"),
                consumer(1, Protocol.CONSUMER, "uniform", true, Set.of(), -1, "t-1|t-2"),
                consumer(2, Protocol.CONSUMER, "uniform", true, Set.of(Fault.freeze()), -1, ""),
                consumer(3, Protocol.CONSUMER, "uniform", false, Set.of(), -1, "a-0")
            )
        );
        var view = new AdminView(
            GroupType.CONSUMER,
            GroupState.RECONCILING,
            12,
            11,
            List.of(
                member(0, "instance-0", 11, "t-0", "t-0"),
                member(1, "", 11, "t-1|t-2", "t-1|a-1"),
                member(3, "", 11, "a-0", "a-0"),
                member(9, "", 11, "a-1", "a-1")
            ),
            Map.of(new PartitionRef("t", 1), 7L)
        );

        var snapshot = SnapshotAssembler.assemble(group, view);

        assertThat(snapshot.id()).isEqualTo("g");
        assertThat(snapshot.type()).isEqualTo(GroupType.CONSUMER);
        assertThat(snapshot.state()).isEqualTo(GroupState.RECONCILING);
        assertThat(snapshot.epoch()).isEqualTo(12);
        assertThat(snapshot.targetAssignmentEpoch()).isEqualTo(11);
        assertThat(snapshot.generation()).isEqualTo(4);
        assertThat(snapshot.subscribedTopics()).containsExactly("t", "a");
        assertThat(snapshot.autopilot()).isEqualTo(AUTOPILOT_ON);
        assertThat(snapshot.consumers()).extracting(ConsumerSnapshot::id)
            .containsExactly("consumer-0", "consumer-1", "consumer-2", "consumer-3");

        // A member in sync with its target.
        assertThat(snapshot.consumers().get(0)).isEqualTo(new ConsumerSnapshot(
            "consumer-0",
            "g-consumer-0",
            Protocol.CONSUMER,
            true,
            List.of(),
            "member-0",
            "instance-0",
            11,
            4,
            ConsumerStatus.STABLE,
            ConsumerStatus.STABLE,
            "",
            List.of(new PartitionView("t", 0, PartitionState.ASSIGNED)),
            new ConsumerSnapshot.Raw(sortedRefs("t-0"), sortedRefs("t-0"), sortedRefs("t-0"))
        ));

        // A member with a partition to give up and one to receive.
        var reconciling = snapshot.consumers().get(1);
        assertThat(reconciling.coordinatorState()).isEqualTo(ConsumerStatus.RECONCILING);
        assertThat(reconciling.status()).isEqualTo(ConsumerStatus.RECONCILING);
        assertThat(reconciling.partitions()).containsExactly(
            new PartitionView("a", 1, PartitionState.PENDING),
            new PartitionView("t", 1, PartitionState.ASSIGNED),
            new PartitionView("t", 2, PartitionState.REVOKING)
        );
        assertThat(reconciling.raw().targetAssignment())
            .containsExactly(new PartitionRef("a", 1), new PartitionRef("t", 1));

        // A subscribed consumer the coordinator does not list.
        var joining = snapshot.consumers().get(2);
        assertThat(joining.coordinatorState()).isEqualTo(ConsumerStatus.JOINING);
        assertThat(joining.status()).isEqualTo(ConsumerStatus.FROZEN);
        assertThat(joining.faults()).containsExactly(Fault.freeze());
        assertThat(joining.memberId()).isEmpty();
        assertThat(joining.instanceId()).isEmpty();
        assertThat(joining.memberEpoch()).isEqualTo(-1);
        assertThat(joining.partitions()).isEmpty();

        // A consumer asked to leave that the coordinator still lists: its partitions are blanked at
        // once and the raw assignments keep what was reported.
        var stopping = snapshot.consumers().get(3);
        assertThat(stopping.subscribed()).isFalse();
        assertThat(stopping.coordinatorState()).isEqualTo(ConsumerStatus.STOPPING);
        assertThat(stopping.status()).isEqualTo(ConsumerStatus.STOPPED);
        assertThat(stopping.memberId()).isEqualTo("member-3");
        assertThat(stopping.partitions()).isEmpty();
        assertThat(stopping.raw())
            .isEqualTo(new ConsumerSnapshot.Raw(sortedRefs("a-0"), sortedRefs("a-0"), sortedRefs("a-0")));
    }

    @Test
    void aStableMemberWithPartitionsInFlightLocallyIsReconciling() {
        var group = new GroupLocalState(
            "g",
            TOPICS,
            AUTOPILOT_OFF,
            List.of(consumer(0, Protocol.CONSUMER, "uniform", true, Set.of(), -1, "t-0"))
        );
        var view = new AdminView(
            GroupType.CONSUMER,
            GroupState.STABLE,
            5,
            5,
            List.of(member(0, "", 5, "t-0|t-1", "t-0|t-1")),
            Map.of()
        );

        var consumer = SnapshotAssembler.assemble(group, view).consumers().get(0);

        assertThat(consumer.coordinatorState()).isEqualTo(ConsumerStatus.STABLE);
        assertThat(consumer.status()).isEqualTo(ConsumerStatus.RECONCILING);
        assertThat(consumer.partitions()).containsExactly(
            new PartitionView("t", 0, PartitionState.ASSIGNED),
            new PartitionView("t", 1, PartitionState.PENDING)
        );
    }

    /**
     * A classic group reports no target: the target mirrors the assignment. While the group
     * rebalances, only the consumers whose own assignor is eager are blanked.
     */
    @ParameterizedTest(name = "{0} -> eager blanked={1}")
    @CsvSource({
        "STABLE,               false, STABLE",
        "PREPARING_REBALANCE,  true,  RECONCILING",
        "COMPLETING_REBALANCE, true,  RECONCILING",
    })
    void mergesAClassicGroupWithOneAssignorPerConsumer(
        GroupState state,
        boolean eagerBlanked,
        ConsumerStatus expectedStatus
    ) {
        var group = new GroupLocalState(
            "g",
            TOPICS,
            AUTOPILOT_OFF,
            List.of(
                consumer(0, Protocol.CLASSIC, "range", true, Set.of(), 3, ""),
                consumer(1, Protocol.CLASSIC, "cooperative-sticky", true, Set.of(), 2, "t-1")
            )
        );
        var view = new AdminView(
            GroupType.CLASSIC,
            state,
            -1,
            -1,
            List.of(classicMember(0, "t-0"), classicMember(1, "t-1")),
            Map.of()
        );

        var snapshot = SnapshotAssembler.assemble(group, view);

        assertThat(snapshot.type()).isEqualTo(GroupType.CLASSIC);
        assertThat(snapshot.epoch()).isEqualTo(-1);
        assertThat(snapshot.generation()).isEqualTo(3);

        var eager = snapshot.consumers().get(0);
        assertThat(eager.status()).isEqualTo(expectedStatus);
        assertThat(eager.generation()).isEqualTo(3);
        assertThat(eager.raw().targetAssignment()).isEqualTo(eager.raw().assignment()).isEqualTo(sortedRefs("t-0"));
        if (eagerBlanked) {
            assertThat(eager.partitions()).isEmpty();
        } else {
            assertThat(eager.partitions()).containsExactly(new PartitionView("t", 0, PartitionState.ASSIGNED));
        }

        var cooperative = snapshot.consumers().get(1);
        assertThat(cooperative.status()).isEqualTo(expectedStatus);
        assertThat(cooperative.partitions()).containsExactly(new PartitionView("t", 1, PartitionState.ASSIGNED));
    }

    @Test
    void aGroupTheCoordinatorHasNotReportedYet() {
        var group = new GroupLocalState(
            "g",
            TOPICS,
            AUTOPILOT_OFF,
            List.of(
                consumer(0, Protocol.CONSUMER, "", true, Set.of(), -1, ""),
                consumer(1, Protocol.CONSUMER, "", false, Set.of(), -1, "")
            )
        );

        var snapshot = SnapshotAssembler.assemble(group, AdminView.unknown());

        assertThat(snapshot.type()).isEqualTo(GroupType.UNKNOWN);
        assertThat(snapshot.state()).isEqualTo(GroupState.UNKNOWN);
        assertThat(snapshot.epoch()).isEqualTo(-1);
        assertThat(snapshot.targetAssignmentEpoch()).isEqualTo(-1);
        assertThat(snapshot.generation()).isEqualTo(-1);
        assertThat(snapshot.consumers()).extracting(ConsumerSnapshot::status)
            .containsExactly(ConsumerStatus.JOINING, ConsumerStatus.STOPPED);
        assertThat(snapshot.consumers()).extracting(ConsumerSnapshot::coordinatorState)
            .containsExactly(ConsumerStatus.JOINING, ConsumerStatus.STOPPED);
    }

    @Test
    void aGroupWithoutConsumers() {
        var snapshot = SnapshotAssembler.assemble(
            new GroupLocalState("g", List.of(), AUTOPILOT_OFF, List.of()),
            AdminView.unknown()
        );

        assertThat(snapshot.consumers()).isEmpty();
        assertThat(snapshot.generation()).isEqualTo(-1);
        assertThat(snapshot.lag()).isEmpty();
    }

    /**
     * The lag has one list per subscribed topic, in the order of the subscription, with one entry
     * per partition. A partition without a known lag gets -1, and the lag of a partition the group
     * does not subscribe to, or beyond the partition count, is left out.
     */
    @Test
    void lagIsDensePerSubscribedTopic() {
        var view = new AdminView(
            GroupType.CONSUMER,
            GroupState.STABLE,
            1,
            1,
            List.of(),
            Map.of(
                new PartitionRef("t", 0), 0L,
                new PartitionRef("t", 2), 9L,
                new PartitionRef("t", 3), 1L,
                new PartitionRef("a", 1), 2L,
                new PartitionRef("other", 0), 100L
            )
        );

        var snapshot = SnapshotAssembler.assemble(new GroupLocalState("g", TOPICS, AUTOPILOT_OFF, List.of()), view);

        assertThat(snapshot.lag()).containsExactly(
            Map.entry("t", List.of(0L, -1L, 9L)),
            Map.entry("a", List.of(-1L, 2L))
        );
        var unknown = SnapshotAssembler.assemble(
            new GroupLocalState("g", TOPICS, AUTOPILOT_OFF, List.of()), AdminView.unknown()
        );
        assertThat(unknown.lag())
            .containsExactly(Map.entry("t", List.of(-1L, -1L, -1L)), Map.entry("a", List.of(-1L, -1L)));
    }

    /**
     * The failure message of the runner is the single source of the {@code FAILED} status: a
     * non-empty message gives the status and is carried as is.
     */
    @ParameterizedTest(name = "failure={0} subscribed={1} -> {2}")
    @CsvSource({
        "'',                   true,  JOINING",
        "'',                   false, STOPPED",
        "Authorization failed, true,  FAILED",
        "Authorization failed, false, FAILED",
    })
    void aFailedConsumerCarriesItsFailure(String failure, boolean subscribed, ConsumerStatus expected) {
        var consumer = new ConsumerLocalState(
            "consumer-0",
            "g-consumer-0",
            Protocol.CONSUMER,
            "",
            subscribed,
            Set.of(),
            failure,
            -1,
            Set.of()
        );
        var group = new GroupLocalState("g", TOPICS, AUTOPILOT_OFF, List.of(consumer));

        var snapshot = SnapshotAssembler.assemble(group, AdminView.unknown()).consumers().get(0);

        assertThat(snapshot.failure()).isEqualTo(failure);
        assertThat(snapshot.status()).isEqualTo(expected);
    }

    @Test
    void faultsAreListedInAStableOrder() {
        var group = new GroupLocalState(
            "g",
            TOPICS,
            AUTOPILOT_OFF,
            List.of(consumer(
                0,
                Protocol.CONSUMER,
                "",
                true,
                Set.of(Fault.connectionReset(), Fault.freeze(), Fault.networkIsolation()),
                -1,
                ""
            ))
        );

        var consumer = SnapshotAssembler.assemble(group, AdminView.unknown()).consumers().get(0);

        assertThat(consumer.faults())
            .containsExactly(Fault.freeze(), Fault.networkIsolation(), Fault.connectionReset());
    }

    private static ConsumerLocalState consumer(
        int index,
        Protocol protocol,
        String assignor,
        boolean subscribed,
        Set<Fault> faults,
        int generation,
        String localAssignment
    ) {
        return new ConsumerLocalState(
            "consumer-" + index,
            "g-consumer-" + index,
            protocol,
            assignor,
            subscribed,
            faults,
            "",
            generation,
            refs(localAssignment)
        );
    }

    private static AdminView.Member member(int index, String instanceId, int epoch, String assignment, String target) {
        return new AdminView.Member(
            "g-consumer-" + index,
            "member-" + index,
            instanceId,
            epoch,
            refs(assignment),
            Optional.of(refs(target))
        );
    }

    private static AdminView.Member classicMember(int index, String assignment) {
        return new AdminView.Member(
            "g-consumer-" + index,
            "member-" + index,
            "",
            -1,
            refs(assignment),
            Optional.empty()
        );
    }

    private static List<PartitionRef> sortedRefs(String partitions) {
        return refs(partitions).stream().sorted().toList();
    }
}
