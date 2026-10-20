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

import java.util.List;
import java.util.Objects;

import io.github.dajac.kaos.TopicSpec;

/**
 * An immutable copy of what the simulator itself knows about one group.
 *
 * @param id the group id
 * @param subscribedTopics the topics the group subscribes to, with their partition counts
 * @param consumers the local state of the consumers, in the order of the group
 */
public record GroupLocalState(
    String id,
    List<TopicSpec> subscribedTopics,
    List<ConsumerLocalState> consumers
) {
    public GroupLocalState {
        Objects.requireNonNull(id, "id");
        subscribedTopics = List.copyOf(subscribedTopics);
        consumers = List.copyOf(consumers);
    }
}
