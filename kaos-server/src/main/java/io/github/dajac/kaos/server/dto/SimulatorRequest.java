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
package io.github.dajac.kaos.server.dto;

import java.util.List;

import io.github.dajac.kaos.SimulatorSpec;

/**
 * The body of {@code POST /api/v1/simulator}: the whole simulation, created and started at once. It
 * has the shape of the {@code spec} of {@code GET /api/v1/simulator}, so a client can send that back.
 *
 * @param topics the topics
 * @param producers the producers
 * @param groups the consumer groups
 */
public record SimulatorRequest(List<TopicRequest> topics, List<ProducerRequest> producers, List<GroupRequest> groups) {

    /** The spec this request describes. */
    public SimulatorSpec toSpec() {
        return new SimulatorSpec(
            topics == null ? List.of() : topics.stream().map(TopicRequest::toSpec).toList(),
            producers == null ? List.of() : producers.stream().map(ProducerRequest::toSpec).toList(),
            groups == null ? List.of() : groups.stream().map(GroupRequest::toSpec).toList()
        );
    }
}
