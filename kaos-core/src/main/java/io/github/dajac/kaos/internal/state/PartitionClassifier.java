package io.github.dajac.kaos.internal.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import io.github.dajac.kaos.GroupState;
import io.github.dajac.kaos.PartitionRef;
import io.github.dajac.kaos.PartitionState;
import io.github.dajac.kaos.PartitionView;
import io.github.dajac.kaos.Protocol;
import io.github.dajac.kaos.internal.config.Assignors;

/**
 * Derives the state of each partition for one consumer from three sets: the current and the target
 * assignment reported by the coordinator, and the assignment the consumer holds locally. Pure
 * functions.
 */
public final class PartitionClassifier {
    private PartitionClassifier() {}

    /**
     * The non-empty partitions of a consumer, in the order of {@link PartitionRef}.
     *
     * @param consumer the local state of the consumer
     * @param groupState the state of its group
     * @param assignment the current assignment reported by the coordinator
     * @param targetAssignment the target assignment, see {@link AdminView#targetAssignmentOf}
     */
    public static List<PartitionView> classify(
        ConsumerLocalState consumer,
        GroupState groupState,
        Set<PartitionRef> assignment,
        Set<PartitionRef> targetAssignment
    ) {
        var blank = isOutOfGroup(consumer.subscribed(), assignment, targetAssignment)
            || isEagerMidRebalance(consumer.protocol(), consumer.assignor(), groupState);
        if (blank) {
            return List.of();
        }

        var candidates = new TreeSet<>(assignment);
        candidates.addAll(targetAssignment);
        candidates.addAll(consumer.localAssignment());

        var partitions = new ArrayList<PartitionView>();
        for (var ref : candidates) {
            var state = classify(
                consumer.protocol(),
                assignment.contains(ref),
                targetAssignment.contains(ref),
                consumer.localAssignment().contains(ref)
            );
            if (state != PartitionState.EMPTY) {
                partitions.add(new PartitionView(ref.topic(), ref.partition(), state));
            }
        }
        return partitions;
    }

    /**
     * Whether every partition of the consumer is blanked because the consumer is not in the group:
     * it was asked to leave, which counts at once without waiting for the coordinator, or the
     * coordinator has neither a current nor a target partition for it.
     */
    public static boolean isOutOfGroup(
        boolean subscribed,
        Set<PartitionRef> assignment,
        Set<PartitionRef> targetAssignment
    ) {
        return !subscribed || (assignment.isEmpty() && targetAssignment.isEmpty());
    }

    /**
     * Whether every partition of the consumer is blanked because of an eager rebalance. A classic
     * consumer with an eager assignor gives up all its partitions before it rejoins, while the
     * coordinator keeps reporting the last stable assignment until the rebalance completes. The
     * assignor is the effective one of the consumer itself, so consumers of one group can differ.
     */
    public static boolean isEagerMidRebalance(Protocol protocol, String assignor, GroupState groupState) {
        return protocol == Protocol.CLASSIC
            && Assignors.isEagerClassic(assignor)
            && (groupState == GroupState.PREPARING_REBALANCE || groupState == GroupState.COMPLETING_REBALANCE);
    }

    /**
     * The state of one partition of a consumer that is not blanked.
     *
     * <p>A classic consumer has no target distinct from its current assignment, so a partition is
     * assigned when the coordinator reports it and empty otherwise. For a consumer of the consumer
     * protocol, the target and the local assignment decide: both is assigned, target only is
     * pending, and held locally or by the coordinator without being a target is revoking.
     */
    public static PartitionState classify(Protocol protocol, boolean inCurrent, boolean inTarget, boolean inLocal) {
        if (protocol == Protocol.CLASSIC) {
            return inCurrent ? PartitionState.ASSIGNED : PartitionState.EMPTY;
        }
        if (inTarget) {
            return inLocal ? PartitionState.ASSIGNED : PartitionState.PENDING;
        }
        return inCurrent || inLocal ? PartitionState.REVOKING : PartitionState.EMPTY;
    }
}
