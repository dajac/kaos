package io.github.dajac.kaos.internal.state;

import static io.github.dajac.kaos.PartitionState.ASSIGNED;
import static io.github.dajac.kaos.PartitionState.EMPTY;
import static io.github.dajac.kaos.PartitionState.PENDING;
import static io.github.dajac.kaos.PartitionState.REVOKING;
import static io.github.dajac.kaos.Protocol.CLASSIC;
import static io.github.dajac.kaos.Protocol.CONSUMER;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.dajac.kaos.GroupState;
import io.github.dajac.kaos.PartitionRef;
import io.github.dajac.kaos.PartitionState;
import io.github.dajac.kaos.PartitionView;
import io.github.dajac.kaos.Protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

class PartitionClassifierTest {
    private static final PartitionRef T0 = new PartitionRef("t", 0);
    private static final PartitionRef T1 = new PartitionRef("t", 1);
    private static final PartitionRef T2 = new PartitionRef("t", 2);
    private static final PartitionRef A5 = new PartitionRef("a", 5);

    /** The full truth table of (current, target, local) for both protocols. */
    @ParameterizedTest(name = "{0} current={1} target={2} local={3} -> {4}")
    @CsvSource({
        "CONSUMER, false, false, false, EMPTY",
        "CONSUMER, true,  false, false, REVOKING",
        "CONSUMER, false, true,  false, PENDING",
        "CONSUMER, true,  true,  false, PENDING",
        "CONSUMER, false, false, true,  REVOKING",
        "CONSUMER, true,  false, true,  REVOKING",
        "CONSUMER, false, true,  true,  ASSIGNED",
        "CONSUMER, true,  true,  true,  ASSIGNED",
        "CLASSIC,  false, false, false, EMPTY",
        "CLASSIC,  true,  false, false, ASSIGNED",
        "CLASSIC,  false, true,  false, EMPTY",
        "CLASSIC,  true,  true,  false, ASSIGNED",
        "CLASSIC,  false, false, true,  EMPTY",
        "CLASSIC,  true,  false, true,  ASSIGNED",
        "CLASSIC,  false, true,  true,  EMPTY",
        "CLASSIC,  true,  true,  true,  ASSIGNED",
    })
    void classifiesOnePartition(
        Protocol protocol,
        boolean inCurrent,
        boolean inTarget,
        boolean inLocal,
        PartitionState expected
    ) {
        assertThat(PartitionClassifier.classify(protocol, inCurrent, inTarget, inLocal)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "subscribed={0} current={1} target={2} -> {3}")
    @CsvSource({
        "true,  true,  true,  false",
        "true,  true,  false, false",
        "true,  false, true,  false",
        "true,  false, false, true",
        "false, true,  true,  true",
        "false, false, false, true",
    })
    void outOfGroup(boolean subscribed, boolean hasCurrent, boolean hasTarget, boolean expected) {
        Set<PartitionRef> assignment = hasCurrent ? Set.of(T0) : Set.of();
        Set<PartitionRef> target = hasTarget ? Set.of(T1) : Set.of();

        assertThat(PartitionClassifier.isOutOfGroup(subscribed, assignment, target)).isEqualTo(expected);
    }

    /** Every classic assignor, the default and both protocols against every group state. */
    static Stream<Arguments> eagerMidRebalanceCases() {
        var cases = Stream.<Arguments>builder();
        for (var state : GroupState.values()) {
            var rebalancing =
                state == GroupState.PREPARING_REBALANCE || state == GroupState.COMPLETING_REBALANCE;
            for (var eager : new String[] {"", "range", "roundrobin", "sticky"}) {
                cases.add(Arguments.of(CLASSIC, eager, state, rebalancing));
            }
            cases.add(Arguments.of(CLASSIC, "cooperative-sticky", state, false));
            cases.add(Arguments.of(CONSUMER, "range", state, false));
            cases.add(Arguments.of(CONSUMER, "uniform", state, false));
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @MethodSource("eagerMidRebalanceCases")
    void eagerMidRebalance(Protocol protocol, String assignor, GroupState state, boolean expected) {
        assertThat(PartitionClassifier.isEagerMidRebalance(protocol, assignor, state)).isEqualTo(expected);
    }

    @Test
    void listsTheNonEmptyPartitionsOfAConsumerProtocolConsumerInOrder() {
        var consumer = consumer(CONSUMER, "uniform", true, Set.of(T0, T2));

        assertThat(PartitionClassifier.classify(consumer, GroupState.RECONCILING, Set.of(T0, T2), Set.of(T0, T1, A5)))
            .containsExactly(
                new PartitionView("a", 5, PENDING),
                new PartitionView("t", 0, ASSIGNED),
                new PartitionView("t", 1, PENDING),
                new PartitionView("t", 2, REVOKING)
            );
    }

    @Test
    void leavesOutTheEmptyPartitionsOfAClassicConsumer() {
        var consumer = consumer(CLASSIC, "range", true, Set.of(T0, T1));

        assertThat(PartitionClassifier.classify(consumer, GroupState.STABLE, Set.of(T0), Set.of(T0)))
            .containsExactly(new PartitionView("t", 0, ASSIGNED));
        assertThat(PartitionClassifier.classify(CLASSIC, false, false, true)).isEqualTo(EMPTY);
    }

    @ParameterizedTest
    @CsvSource({"CONSUMER, uniform", "CLASSIC, cooperative-sticky"})
    void blanksAConsumerThatIsOutOfTheGroup(Protocol protocol, String assignor) {
        var left = consumer(protocol, assignor, false, Set.of(T0));
        var unknownToTheCoordinator = consumer(protocol, assignor, true, Set.of(T0));

        assertThat(PartitionClassifier.classify(left, GroupState.STABLE, Set.of(T0), Set.of(T0))).isEmpty();
        assertThat(PartitionClassifier.classify(unknownToTheCoordinator, GroupState.STABLE, Set.of(), Set.of()))
            .isEmpty();
    }

    /**
     * Two classic consumers of one rebalancing group: the one with an eager assignor is blanked and
     * the cooperative one keeps its partitions.
     */
    @Test
    void eagerBlankingFollowsTheAssignorOfEachConsumer() {
        var eager = consumer(CLASSIC, "range", true, Set.of());
        var cooperative = consumer(CLASSIC, "cooperative-sticky", true, Set.of(T1));

        assertThat(PartitionClassifier.classify(eager, GroupState.PREPARING_REBALANCE, Set.of(T0), Set.of(T0)))
            .isEmpty();
        assertThat(PartitionClassifier.classify(cooperative, GroupState.PREPARING_REBALANCE, Set.of(T1), Set.of(T1)))
            .containsExactly(new PartitionView("t", 1, ASSIGNED));
        assertThat(PartitionClassifier.classify(eager, GroupState.STABLE, Set.of(T0), Set.of(T0)))
            .containsExactly(new PartitionView("t", 0, ASSIGNED));
    }

    private static ConsumerLocalState consumer(
        Protocol protocol,
        String assignor,
        boolean subscribed,
        Set<PartitionRef> localAssignment
    ) {
        return new ConsumerLocalState(
            "consumer-0",
            "g-consumer-0",
            protocol,
            assignor,
            subscribed,
            Set.of(),
            "",
            -1,
            localAssignment
        );
    }
}
