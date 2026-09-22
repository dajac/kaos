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

import java.util.Objects;
import java.util.Set;

import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.PartitionRef;
import io.github.dajac.kaos.Protocol;

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
 * @param failure the message of the unrecoverable error the runner stopped on, never empty for a
 *     failed runner; empty while the runner has not failed
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
    String failure,
    int generation,
    Set<PartitionRef> localAssignment
) {
    public ConsumerLocalState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(protocol, "protocol");
        assignor = Objects.requireNonNullElse(assignor, "");
        faults = Set.copyOf(faults);
        failure = Objects.requireNonNullElse(failure, "");
        localAssignment = Set.copyOf(localAssignment);
    }

    /** Whether the runner stopped on an unrecoverable error: it has a failure message. */
    public boolean failed() {
        return !failure.isEmpty();
    }
}
