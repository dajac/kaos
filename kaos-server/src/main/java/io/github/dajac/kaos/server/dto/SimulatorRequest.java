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
