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

import java.util.Objects;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.ConsumerSettings;
import io.github.dajac.kaos.Protocol;

/**
 * The {@code consumerDefaults} of a group in a request body. Each missing value takes the default of
 * {@link ConfigMetadata}, so that a partial body never binds a missing number as 0.
 *
 * @param protocol the group protocol
 * @param assignor the assignor name; empty for the default of the protocol
 * @param staticMember whether the consumers are static members
 * @param rackId the rack id; empty for no rack
 * @param pollTimeoutMs the poll timeout
 * @param pollDelayMs the pause between two polls
 * @param maxPollIntervalMs the max poll interval
 */
public record ConsumerSettingsRequest(
    Protocol protocol,
    String assignor,
    Boolean staticMember,
    String rackId,
    Integer pollTimeoutMs,
    Integer pollDelayMs,
    Integer maxPollIntervalMs
) {

    /** The settings this request describes, with the defaults filled in. */
    public ConsumerSettings toSettings() {
        return new ConsumerSettings(
            protocol,
            assignor,
            Objects.requireNonNullElse(staticMember, ConfigMetadata.STATIC_MEMBER.booleanDefault()),
            rackId,
            Objects.requireNonNullElse(pollTimeoutMs, ConfigMetadata.POLL_TIMEOUT_MS.intDefault()),
            Objects.requireNonNullElse(pollDelayMs, ConfigMetadata.POLL_DELAY_MS.intDefault()),
            Objects.requireNonNullElse(maxPollIntervalMs, ConfigMetadata.MAX_POLL_INTERVAL_MS.intDefault())
        );
    }
}
