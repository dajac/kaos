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

/**
 * The immutable description of a whole simulation. A {@code null} list is read as empty, so that a
 * request body can leave it out.
 *
 * @param topics the topics created and owned by the simulator
 * @param producers the load generators
 * @param groups the consumer groups
 */
public record SimulatorSpec(List<TopicSpec> topics, List<ProducerSpec> producers, List<GroupSpec> groups) {
    public SimulatorSpec {
        topics = topics == null ? List.of() : List.copyOf(topics);
        producers = producers == null ? List.of() : List.copyOf(producers);
        groups = groups == null ? List.of() : List.copyOf(groups);
    }
}
