package io.github.dajac.kaos.internal.state;

import io.github.dajac.kaos.GroupState;
import io.github.dajac.kaos.GroupType;
import io.github.dajac.kaos.PartitionRef;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the group coordinator and the offsets say about one group at one poll. It is immutable and
 * holds no Kafka type: the poller translates the admin client results into it. An epoch that is not
 * reported is {@code -1}.
 *
 * @param type the group type
 * @param state the group state
 * @param epoch the group epoch of a consumer group
 * @param targetAssignmentEpoch the epoch of the target assignment of a consumer group
 * @param members the members the coordinator lists
 * @param lag the lag of every partition it is known for: the end offset minus the committed offset
 */
public record AdminView(
    GroupType type,
    GroupState state,
    int epoch,
    int targetAssignmentEpoch,
    List<Member> members,
    Map<PartitionRef, Long> lag
) {
    public AdminView {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        members = List.copyOf(members);
        lag = Map.copyOf(lag);
    }

    /** The view of a group the coordinator has not reported yet. */
    public static AdminView unknown() {
        return new AdminView(GroupType.UNKNOWN, GroupState.UNKNOWN, -1, -1, List.of(), Map.of());
    }

    /** The member with the given {@code client.id}, if the coordinator lists one. */
    public Optional<Member> member(String clientId) {
        return members.stream().filter(member -> member.clientId().equals(clientId)).findFirst();
    }

    /**
     * The target assignment of a member. It is the reported one when there is one. Otherwise a
     * member of a consumer group has an empty target, and a member of any other group, which has no
     * notion of target, mirrors its current assignment.
     */
    public Set<PartitionRef> targetAssignmentOf(Member member) {
        return member.targetAssignment()
            .orElse(type == GroupType.CONSUMER ? Set.of() : member.assignment());
    }

    /**
     * One member as the coordinator describes it.
     *
     * @param clientId the {@code client.id}, which links the member to its simulated consumer
     * @param memberId the member id
     * @param instanceId the {@code group.instance.id}; empty for a dynamic member
     * @param memberEpoch the member epoch; {@code -1} when it is not reported
     * @param assignment the current assignment
     * @param targetAssignment the target assignment; empty when the coordinator reports none
     */
    public record Member(
        String clientId,
        String memberId,
        String instanceId,
        int memberEpoch,
        Set<PartitionRef> assignment,
        Optional<Set<PartitionRef>> targetAssignment
    ) {
        public Member {
            Objects.requireNonNull(clientId, "clientId");
            memberId = Objects.requireNonNullElse(memberId, "");
            instanceId = Objects.requireNonNullElse(instanceId, "");
            assignment = Set.copyOf(assignment);
            targetAssignment = targetAssignment.map(Set::copyOf);
        }
    }
}
