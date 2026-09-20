package io.github.dajac.kaos.internal.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dajac.kaos.ConsumerStatus;
import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.GroupState;
import io.github.dajac.kaos.GroupType;
import io.github.dajac.kaos.PartitionRef;
import io.github.dajac.kaos.Protocol;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class StatusDeriverTest {
    private static final PartitionRef T0 = new PartitionRef("t", 0);
    private static final PartitionRef T1 = new PartitionRef("t", 1);
    private static final int TARGET_EPOCH = 7;

    /**
     * A member of a consumer group: {@code target} is "none" when the coordinator reports no target,
     * which reads as an empty target.
     */
    @ParameterizedTest(name = "assignment={0} target={1} memberEpoch={2} -> {3}")
    @CsvSource({
        "t-0,     t-0,     7, STABLE",
        "t-0,     t-0,     8, STABLE",
        "t-0,     t-0,     6, RECONCILING",
        "t-0,     t-0|t-1, 7, RECONCILING",
        "t-0|t-1, t-0,     7, RECONCILING",
        "'',      '',      7, STABLE",
        "'',      none,    7, STABLE",
        "t-0,     none,    7, RECONCILING",
    })
    void memberStateInAConsumerGroup(String assignment, String target, int memberEpoch, ConsumerStatus expected) {
        var member = member(memberEpoch, refs(assignment), "none".equals(target) ? null : refs(target));
        var view = view(GroupType.CONSUMER, GroupState.STABLE, member);

        assertThat(StatusDeriver.memberState(view, member)).isEqualTo(expected);
    }

    /** A group that is not a consumer group: only the group state counts, for every group state. */
    static Stream<Arguments> groupStateCases() {
        return Stream.of(GroupType.CLASSIC, GroupType.UNKNOWN).flatMap(type ->
            Arrays.stream(GroupState.values()).map(state -> {
                var rebalancing =
                    state == GroupState.PREPARING_REBALANCE || state == GroupState.COMPLETING_REBALANCE;
                return Arguments.of(type, state, rebalancing ? ConsumerStatus.RECONCILING : ConsumerStatus.STABLE);
            })
        );
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("groupStateCases")
    void memberStateInAnyOtherGroupFollowsTheGroupState(GroupType type, GroupState state, ConsumerStatus expected) {
        // The member epoch and the missing target play no role here.
        var member = member(-1, Set.of(T0), null);

        assertThat(StatusDeriver.memberState(view(type, state, member), member)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "listed={0} subscribed={1} -> {2}")
    @CsvSource({
        "false, true,  JOINING",
        "false, false, STOPPED",
        "true,  false, STOPPING",
        "true,  true,  RECONCILING",
    })
    void coordinatorState(boolean listed, boolean subscribed, ConsumerStatus expected) {
        var member = member(TARGET_EPOCH, Set.of(T0), Set.of(T0, T1));
        var view = view(GroupType.CONSUMER, GroupState.RECONCILING, member);

        assertThat(StatusDeriver.coordinatorState(view, listed ? Optional.of(member) : Optional.empty(), subscribed))
            .isEqualTo(expected);
    }

    /**
     * The priority of the status. {@code faults} lists fault ids separated by "|". The assignments
     * are in sync, so the coordinator state is the last resort.
     */
    @ParameterizedTest(name = "failed={0} subscribed={1} faults={2} coordinator={3} -> {4}")
    @CsvSource({
        "true,  true,  '',                                          STABLE,      FAILED",
        "true,  false, freeze|network-isolation,                    STOPPING,    FAILED",
        "false, false, '',                                          STOPPED,     STOPPED",
        "false, false, '',                                          STOPPING,    STOPPED",
        "false, false, freeze|network-isolation|connection-reset,   STOPPING,    STOPPED",
        "false, true,  freeze,                                      STABLE,      FROZEN",
        "false, true,  freeze|network-isolation,                    STABLE,      FROZEN",
        "false, true,  freeze|connection-reset,                     JOINING,     FROZEN",
        "false, true,  network-isolation,                           STABLE,      ISOLATED",
        "false, true,  connection-reset,                            STABLE,      ISOLATED",
        "false, true,  network-isolation|connection-reset,          RECONCILING, ISOLATED",
        "false, true,  '',                                          JOINING,     JOINING",
        "false, true,  '',                                          RECONCILING, RECONCILING",
        "false, true,  '',                                          STABLE,      STABLE",
    })
    void statusPriority(
        boolean failed,
        boolean subscribed,
        String faults,
        ConsumerStatus coordinatorState,
        ConsumerStatus expected
    ) {
        var consumer = consumer(Protocol.CONSUMER, subscribed, faults(faults), failed, Set.of(T0));

        assertThat(StatusDeriver.status(consumer, coordinatorState, Set.of(T0), Set.of(T0))).isEqualTo(expected);
    }

    /**
     * The override of a {@code STABLE} coordinator state while partitions are in flight. It only
     * applies to a consumer of the consumer protocol whose coordinator state is {@code STABLE}.
     */
    @ParameterizedTest(name = "{0} coordinator={1} assignment={2} target={3} local={4} -> {5}")
    @CsvSource({
        "CONSUMER, STABLE,      t-0,     t-0,     t-0,     STABLE",
        "CONSUMER, STABLE,      t-0|t-1, t-0|t-1, t-0,     RECONCILING",
        "CONSUMER, STABLE,      t-0,     t-0,     t-0|t-1, RECONCILING",
        "CONSUMER, STABLE,      t-0|t-1, t-0,     t-0,     RECONCILING",
        "CONSUMER, JOINING,     '',      '',      t-0,     JOINING",
        "CONSUMER, RECONCILING, t-0,     t-0|t-1, t-0,     RECONCILING",
        "CLASSIC,  STABLE,      t-0|t-1, t-0|t-1, t-0,     STABLE",
        "CLASSIC,  STABLE,      t-0,     t-0,     t-0|t-1, STABLE",
    })
    void stableIsOverriddenWhilePartitionsAreInFlight(
        Protocol protocol,
        ConsumerStatus coordinatorState,
        String assignment,
        String target,
        String local,
        ConsumerStatus expected
    ) {
        var consumer = consumer(protocol, true, Set.of(), false, refs(local));

        assertThat(StatusDeriver.status(consumer, coordinatorState, refs(assignment), refs(target)))
            .isEqualTo(expected);
    }

    @ParameterizedTest(name = "assignment={0} target={1} local={2} -> {3}")
    @CsvSource({
        "'',      '',      '',      false",
        "t-0,     t-0,     t-0,     false",
        "'',      t-0,     t-0,     false",
        "t-0,     t-0|t-1, t-0,     true",
        "t-0,     t-0,     t-0|t-1, true",
        "t-0|t-1, t-0,     t-0,     true",
        "t-0,     '',      '',      true",
    })
    void mismatch(String assignment, String target, String local, boolean expected) {
        assertThat(StatusDeriver.hasMismatch(refs(assignment), refs(target), refs(local))).isEqualTo(expected);
    }

    private static AdminView view(GroupType type, GroupState state, AdminView.Member member) {
        return new AdminView(type, state, 9, TARGET_EPOCH, List.of(member), Map.of());
    }

    private static AdminView.Member member(int memberEpoch, Set<PartitionRef> assignment, Set<PartitionRef> target) {
        return new AdminView.Member("g-consumer-0", "member-0", "", memberEpoch, assignment, Optional.ofNullable(target));
    }

    private static ConsumerLocalState consumer(
        Protocol protocol,
        boolean subscribed,
        Set<Fault> faults,
        boolean failed,
        Set<PartitionRef> localAssignment
    ) {
        return new ConsumerLocalState(
            "consumer-0",
            "g-consumer-0",
            protocol,
            "",
            subscribed,
            faults,
            failed,
            -1,
            localAssignment
        );
    }

    private static Set<Fault> faults(String ids) {
        return tokens(ids).map(Fault::of).collect(Collectors.toSet());
    }

    /** Parses "t-0|t-1" into partition references. */
    static Set<PartitionRef> refs(String partitions) {
        return tokens(partitions)
            .map(token -> {
                var dash = token.lastIndexOf('-');
                return new PartitionRef(token.substring(0, dash), Integer.parseInt(token.substring(dash + 1)));
            })
            .collect(Collectors.toSet());
    }

    private static Stream<String> tokens(String value) {
        return value == null || value.isBlank() ? Stream.empty() : Arrays.stream(value.split("\\|")).map(String::trim);
    }
}
