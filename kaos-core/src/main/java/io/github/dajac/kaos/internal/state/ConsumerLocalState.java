package io.github.dajac.kaos.internal.state;

import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.PartitionRef;
import io.github.dajac.kaos.Protocol;

import java.util.Objects;
import java.util.Set;

/**
 * An immutable copy of what one simulated consumer knows about itself: its identity, its effective
 * settings that matter to the snapshot, the flags set through its handle and what its runner
 * observed.
 *
 * @param id the consumer id, unique within the group
 * @param clientId the {@code client.id}
 * @param protocol the protocol the consumer speaks at the moment
 * @param assignor the effective assignor name of this consumer; empty selects the default of the
 *     protocol
 * @param subscribed whether the consumer is asked to be in the group
 * @param faults the active faults
 * @param failed whether the runner stopped on an unrecoverable error
 * @param generation the generation the consumer reports; {@code -1} when it has none
 * @param localAssignment the partitions the consumer holds, as its rebalance listener saw them
 */
public record ConsumerLocalState(
    String id,
    String clientId,
    Protocol protocol,
    String assignor,
    boolean subscribed,
    Set<Fault> faults,
    boolean failed,
    int generation,
    Set<PartitionRef> localAssignment
) {
    public ConsumerLocalState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(protocol, "protocol");
        assignor = Objects.requireNonNullElse(assignor, "");
        faults = Set.copyOf(faults);
        localAssignment = Set.copyOf(localAssignment);
    }
}
