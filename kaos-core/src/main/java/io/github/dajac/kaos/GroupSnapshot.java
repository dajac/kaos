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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The live view of one consumer group. It is immutable. An epoch that is not known is {@code -1}.
 *
 * @param id the group id
 * @param type the group type reported by the coordinator
 * @param state the group state reported by the coordinator
 * @param epoch the group epoch of a consumer group
 * @param targetAssignmentEpoch the epoch of the target assignment of a consumer group
 * @param subscribedTopics the names of the topics the group subscribes to
 * @param lag the lag of each subscribed topic, in the order of {@code subscribedTopics}; the list
 *     of a topic has one entry per partition, indexed by partition number. A partition without a committed
 *     offset has its end offset as lag; one whose end offset is not known yet has {@code -1}
 * @param consumers the consumers of the group
 */
public record GroupSnapshot(
    String id,
    GroupType type,
    GroupState state,
    int epoch,
    int targetAssignmentEpoch,
    List<String> subscribedTopics,
    Map<String, List<Long>> lag,
    List<ConsumerSnapshot> consumers
) {
    public GroupSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        subscribedTopics = List.copyOf(subscribedTopics);
        lag = copyInOrder(lag);
        consumers = List.copyOf(consumers);
    }

    private static Map<String, List<Long>> copyInOrder(Map<String, List<Long>> lag) {
        var copy = new LinkedHashMap<String, List<Long>>();
        lag.forEach((topic, values) -> copy.put(Objects.requireNonNull(topic, "topic"), List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }
}
