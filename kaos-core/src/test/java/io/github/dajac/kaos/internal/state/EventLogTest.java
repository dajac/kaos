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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.github.dajac.kaos.AutopilotSnapshot;
import io.github.dajac.kaos.ConsumerSnapshot;
import io.github.dajac.kaos.ConsumerStatus;
import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.GroupSnapshot;
import io.github.dajac.kaos.GroupState;
import io.github.dajac.kaos.GroupType;
import io.github.dajac.kaos.PartitionRef;
import io.github.dajac.kaos.Phase;
import io.github.dajac.kaos.Protocol;
import io.github.dajac.kaos.SimulatorSnapshot;
import org.junit.jupiter.api.Test;

/** The change log is a pure function of two group lists. */
class EventLogTest {

    private static final AutopilotSnapshot OFF = new AutopilotSnapshot(false, 10_000, 30_000);

    @Test
    void reportsNothingWhenNothingChanged() {
        var stable = consumer("consumer-0", true, List.of(), "m0", 3, ConsumerStatus.STABLE, "", "t-0");
        var group = group(GroupState.STABLE, 3, 3, OFF, stable);

        assertThat(EventLog.diff(List.of(group), List.of(group))).isEmpty();
        assertThat(EventLog.diff(List.of(), List.of())).isEmpty();
    }

    @Test
    void reportsGroupsThatAppearAndDisappear() {
        var joining = consumer("consumer-0", true, List.of(), "", -1, ConsumerStatus.JOINING, "", "");
        var group = group(GroupState.UNKNOWN, -1, -1, OFF, joining);

        assertThat(EventLog.diff(List.of(), List.of(group))).containsExactly("[g] group added with 1 consumer(s)");
        assertThat(EventLog.diff(List.of(group), List.of())).containsExactly("[g] group removed");
    }

    @Test
    void reportsTheGroupLevelChanges() {
        var before = group(GroupState.STABLE, 3, 3, OFF);
        var after = new GroupSnapshot(
            "g", GroupType.CLASSIC, GroupState.PREPARING_REBALANCE, 4, 4, 7, List.of("t"),
            new AutopilotSnapshot(true, 100, 200), Map.of("t", List.of(0L)), List.of()
        );

        assertThat(EventLog.diff(List.of(before), List.of(after))).containsExactly(
            "[g] group type: CONSUMER -> CLASSIC",
            "[g] group state: STABLE -> PREPARING_REBALANCE",
            "[g] group epoch: 3 -> 4",
            "[g] target assignment epoch: 3 -> 4",
            "[g] generation: -1 -> 7",
            "[g] autopilot on, every 100..200 ms"
        );
        assertThat(EventLog.diff(List.of(after), List.of(before))).contains("[g] autopilot off");
    }

    @Test
    void reportsTheConsumerLevelChanges() {
        var before = group(
            GroupState.STABLE, 3, 3, OFF,
            consumer("consumer-0", true, List.of(), "m0", 3, ConsumerStatus.STABLE, "", "t-0|t-1"),
            consumer("consumer-1", true, List.of(), "", -1, ConsumerStatus.JOINING, "", "")
        );
        var after = group(
            GroupState.STABLE, 3, 3, OFF,
            consumer("consumer-0", false, List.of(Fault.freeze()), "", -1, ConsumerStatus.STOPPED, "", "t-1"),
            consumer("consumer-1", true, List.of(), "m1", 4, ConsumerStatus.STABLE, "", "t-0")
        );

        assertThat(EventLog.diff(List.of(before), List.of(after))).containsExactly(
            "[g] consumer-0 is asked to leave",
            "[g] consumer-0 fault freeze injected",
            "[g] consumer-0 left the group (member m0)",
            "[g] consumer-0 status: STABLE -> STOPPED",
            "[g] consumer-0 revoked: t-0",
            "[g] consumer-1 joined the group (member m1)",
            "[g] consumer-1 member epoch: -1 -> 4",
            "[g] consumer-1 status: JOINING -> STABLE",
            "[g] consumer-1 assigned: t-0"
        );
    }

    @Test
    void reportsRejoinsSwitchesFailuresAndRemovals() {
        var before = group(
            GroupState.STABLE, 3, 3, OFF,
            consumer("consumer-0", true, List.of(Fault.networkIsolation()), "m0", 3, ConsumerStatus.ISOLATED, "", ""),
            consumer("consumer-1", true, List.of(), "m1", 3, ConsumerStatus.STABLE, "", "")
        );
        var failed = new ConsumerSnapshot(
            "consumer-0", "g-consumer-0", Protocol.CLASSIC, true, List.of(), "m0b", "", 3, 5,
            ConsumerStatus.STABLE, ConsumerStatus.FAILED, "FencedInstanceIdException: fenced", List.of(),
            new ConsumerSnapshot.Raw(List.of(), List.of(), List.of())
        );
        var added = consumer("consumer-2", true, List.of(), "", -1, ConsumerStatus.JOINING, "", "");
        var after = group(GroupState.STABLE, 3, 3, OFF, failed, added);

        assertThat(EventLog.diff(List.of(before), List.of(after))).containsExactly(
            "[g] consumer-0 switched protocol: CONSUMER -> CLASSIC",
            "[g] consumer-0 fault network-isolation cleared",
            "[g] consumer-0 rejoined the group (member m0 -> m0b)",
            "[g] consumer-0 generation: -1 -> 5",
            "[g] consumer-0 status: ISOLATED -> FAILED",
            "[g] consumer-0 failed: FencedInstanceIdException: fenced",
            "[g] consumer-2 added",
            "[g] consumer-1 removed"
        );
    }

    @Test
    void asAListenerItLogsTheDifferenceWithTheLastSnapshot() {
        var log = new EventLog();
        var group = group(GroupState.UNKNOWN, -1, -1, OFF);

        log.onSnapshot(new SimulatorSnapshot(1, Phase.IDLE, false, "", List.of(), List.of(), List.of(group)));
        log.onSnapshot(new SimulatorSnapshot(2, Phase.IDLE, false, "", List.of(), List.of(), List.of(group)));
        log.onSnapshot(new SimulatorSnapshot(3, Phase.IDLE, false, "", List.of(), List.of(), List.of()));
    }

    private static GroupSnapshot group(
        GroupState state,
        int epoch,
        int targetEpoch,
        AutopilotSnapshot autopilot,
        ConsumerSnapshot... consumers
    ) {
        return new GroupSnapshot(
            "g", GroupType.CONSUMER, state, epoch, targetEpoch, -1, List.of("t"), autopilot, Map.of("t", List.of(0L)),
            List.of(consumers)
        );
    }

    private static ConsumerSnapshot consumer(
        String id,
        boolean subscribed,
        List<Fault> faults,
        String memberId,
        int memberEpoch,
        ConsumerStatus status,
        String failure,
        String assignment
    ) {
        var assigned = assignment.isEmpty()
            ? List.<PartitionRef>of()
            : Arrays.stream(assignment.split("\\|")).map(EventLogTest::ref).toList();
        return new ConsumerSnapshot(
            id, "g-" + id, Protocol.CONSUMER, subscribed, faults, memberId, "", memberEpoch, -1, status, status,
            failure, List.of(), new ConsumerSnapshot.Raw(assigned, assigned, assigned)
        );
    }

    private static PartitionRef ref(String name) {
        var dash = name.lastIndexOf('-');
        return new PartitionRef(name.substring(0, dash), Integer.parseInt(name.substring(dash + 1)));
    }
}
