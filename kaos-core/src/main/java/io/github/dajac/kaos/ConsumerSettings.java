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

import java.util.Objects;

/**
 * A complete set of consumer settings. It is used for the {@code consumerDefaults} of a group and
 * for the {@code effective} values of a consumer. A {@code null} protocol, assignor or rack id is
 * read as its default in {@link ConfigMetadata}.
 *
 * @param protocol the group protocol
 * @param assignor the assignor name; empty selects the default of the protocol
 * @param staticMember whether the consumer is a static member ({@code group.instance.id} is set)
 * @param rackId the rack of the consumer; empty means no rack
 * @param pollTimeoutMs the timeout given to {@code poll}
 * @param pollDelayMs the pause between two polls
 * @param maxPollIntervalMs {@code max.poll.interval.ms}
 */
public record ConsumerSettings(
    Protocol protocol,
    String assignor,
    boolean staticMember,
    String rackId,
    int pollTimeoutMs,
    int pollDelayMs,
    int maxPollIntervalMs
) {
    public ConsumerSettings {
        protocol = Objects.requireNonNullElseGet(protocol, ConfigMetadata.PROTOCOL::protocolDefault);
        assignor = Objects.requireNonNullElseGet(assignor, ConfigMetadata.ASSIGNOR::stringDefault);
        rackId = Objects.requireNonNullElseGet(rackId, ConfigMetadata.RACK_ID::stringDefault);
    }

    /** The defaults of {@link ConfigMetadata}. */
    public static ConsumerSettings defaults() {
        return new ConsumerSettings(
            ConfigMetadata.PROTOCOL.protocolDefault(),
            ConfigMetadata.ASSIGNOR.stringDefault(),
            ConfigMetadata.STATIC_MEMBER.booleanDefault(),
            ConfigMetadata.RACK_ID.stringDefault(),
            ConfigMetadata.POLL_TIMEOUT_MS.intDefault(),
            ConfigMetadata.POLL_DELAY_MS.intDefault(),
            ConfigMetadata.MAX_POLL_INTERVAL_MS.intDefault()
        );
    }
}
