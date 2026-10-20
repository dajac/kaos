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

import java.util.List;
import java.util.Objects;

/**
 * The live view of one consumer. It is immutable.
 *
 * <p>The member id and the instance id are empty, and the member epoch is {@code -1}, when they are
 * not known. The failure is empty when the consumer has not failed.
 *
 * @param id the consumer id, unique within the group
 * @param clientId the {@code client.id} of the consumer, unique across groups
 * @param protocol the group protocol the consumer speaks at the moment
 * @param subscribed whether the consumer is asked to be in the group ({@code join()}) or out of it
 *     ({@code leave()})
 * @param faults the active faults, in the order of {@link Fault#all()}
 * @param memberId the member id given by the coordinator
 * @param instanceId the {@code group.instance.id} of a static member
 * @param memberEpoch the member epoch reported by the coordinator
 * @param coordinatorState the membership of the consumer as the coordinator sees it: one of
 *     {@code STOPPED}, {@code STOPPING}, {@code JOINING}, {@code RECONCILING} and {@code STABLE}
 * @param status the single state to show for the consumer, which also accounts for the faults and
 *     for the assignment the consumer holds locally
 * @param failure the message of the unrecoverable error behind the {@code FAILED} status
 * @param partitions the partitions that concern the consumer, in the order of {@link PartitionRef};
 *     a partition in the {@code EMPTY} state is left out
 * @param raw the assignments the partition states are derived from
 */
public record ConsumerSnapshot(
    String id,
    String clientId,
    Protocol protocol,
    boolean subscribed,
    List<Fault> faults,
    String memberId,
    String instanceId,
    int memberEpoch,
    ConsumerStatus coordinatorState,
    ConsumerStatus status,
    String failure,
    List<PartitionView> partitions,
    Raw raw
) {
    public ConsumerSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(protocol, "protocol");
        faults = List.copyOf(faults);
        memberId = Objects.requireNonNullElse(memberId, "");
        instanceId = Objects.requireNonNullElse(instanceId, "");
        Objects.requireNonNull(coordinatorState, "coordinatorState");
        Objects.requireNonNull(status, "status");
        failure = Objects.requireNonNullElse(failure, "");
        partitions = List.copyOf(partitions);
        Objects.requireNonNull(raw, "raw");
    }

    /**
     * The assignments of a consumer as reported, before any rule is applied. Each list is in the
     * order of {@link PartitionRef}.
     *
     * @param assignment the current assignment reported by the coordinator
     * @param targetAssignment the target assignment reported by the coordinator; a classic group
     *     has no target, and this mirrors {@code assignment}
     * @param localAssignment the partitions the consumer itself holds
     */
    public record Raw(
        List<PartitionRef> assignment,
        List<PartitionRef> targetAssignment,
        List<PartitionRef> localAssignment
    ) {
        public Raw {
            assignment = List.copyOf(assignment);
            targetAssignment = List.copyOf(targetAssignment);
            localAssignment = List.copyOf(localAssignment);
        }
    }
}
