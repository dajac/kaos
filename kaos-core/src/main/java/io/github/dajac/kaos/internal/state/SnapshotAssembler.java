package io.github.dajac.kaos.internal.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.dajac.kaos.ConsumerSnapshot;
import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.GroupSnapshot;
import io.github.dajac.kaos.PartitionRef;
import io.github.dajac.kaos.TopicSpec;

/**
 * Merges what the simulator knows about one group with what the coordinator reports about it into
 * the {@link GroupSnapshot} of that group. A pure function.
 */
public final class SnapshotAssembler {
    private static final long UNKNOWN_LAG = -1L;

    private SnapshotAssembler() {}

    /**
     * The snapshot of one group. The consumers come from the local state, in its order; a member
     * the coordinator lists without a simulated consumer behind it is ignored. Consumers and members
     * are matched by {@code client.id}.
     */
    public static GroupSnapshot assemble(GroupLocalState group, AdminView view) {
        var consumers = new ArrayList<ConsumerSnapshot>();
        var generation = -1;
        for (var consumer : group.consumers()) {
            consumers.add(assemble(consumer, view));
            generation = Math.max(generation, consumer.generation());
        }

        return new GroupSnapshot(
            group.id(),
            view.type(),
            view.state(),
            view.epoch(),
            view.targetAssignmentEpoch(),
            generation,
            group.subscribedTopics().stream().map(TopicSpec::name).toList(),
            group.autopilot(),
            denseLag(group.subscribedTopics(), view.lag()),
            consumers
        );
    }

    private static ConsumerSnapshot assemble(ConsumerLocalState consumer, AdminView view) {
        var member = view.member(consumer.clientId());
        Set<PartitionRef> assignment = member.map(AdminView.Member::assignment).orElse(Set.of());
        Set<PartitionRef> targetAssignment = member.map(view::targetAssignmentOf).orElse(Set.of());
        var coordinatorState = StatusDeriver.coordinatorState(view, member, consumer.subscribed());

        return new ConsumerSnapshot(
            consumer.id(),
            consumer.clientId(),
            consumer.protocol(),
            consumer.subscribed(),
            Fault.all().stream().filter(consumer.faults()::contains).toList(),
            member.map(AdminView.Member::memberId).orElse(""),
            member.map(AdminView.Member::instanceId).orElse(""),
            member.map(AdminView.Member::memberEpoch).orElse(-1),
            consumer.generation(),
            coordinatorState,
            StatusDeriver.status(consumer, coordinatorState, assignment, targetAssignment),
            consumer.failure(),
            PartitionClassifier.classify(consumer, view.state(), assignment, targetAssignment),
            new ConsumerSnapshot.Raw(
                sorted(assignment),
                sorted(targetAssignment),
                sorted(consumer.localAssignment())
            )
        );
    }

    /**
     * One list per subscribed topic, with one entry per partition indexed by partition number. A
     * partition without a known lag gets -1.
     */
    private static Map<String, List<Long>> denseLag(List<TopicSpec> topics, Map<PartitionRef, Long> lag) {
        var dense = new LinkedHashMap<String, List<Long>>();
        for (var topic : topics) {
            var values = new ArrayList<Long>(topic.partitions());
            for (var partition = 0; partition < topic.partitions(); partition++) {
                values.add(lag.getOrDefault(new PartitionRef(topic.name(), partition), UNKNOWN_LAG));
            }
            dense.put(topic.name(), values);
        }
        return dense;
    }

    private static List<PartitionRef> sorted(Set<PartitionRef> partitions) {
        return partitions.stream().sorted().toList();
    }
}
