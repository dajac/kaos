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
import java.util.Objects;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.ConsumerSettings;
import io.github.dajac.kaos.ConsumerSpec;
import io.github.dajac.kaos.GroupSpec;

/**
 * A consumer group in a request body. Each missing group setting takes the default of
 * {@link ConfigMetadata}, and so does each missing value of {@code consumerDefaults}. The consumers
 * bind as {@link ConsumerSpec}s: the {@code effective} settings that a client sends back are accepted
 * and ignored, since the group resolves them again.
 *
 * @param id the group id
 * @param subscribedTopics the topics the consumers subscribe to
 * @param sessionTimeoutMs the session timeout, or null for the default
 * @param heartbeatIntervalMs the heartbeat interval, or null for the default
 * @param assignmentIntervalMs the assignment interval, or null for the default
 * @param consumerDefaults the settings the consumers inherit, or null for the defaults
 * @param consumers the consumers
 */
public record GroupRequest(
    String id,
    List<String> subscribedTopics,
    Integer sessionTimeoutMs,
    Integer heartbeatIntervalMs,
    Integer assignmentIntervalMs,
    ConsumerSettingsRequest consumerDefaults,
    List<ConsumerSpec> consumers
) {

    /** The spec this request describes. */
    public GroupSpec toSpec() {
        return new GroupSpec(
            id,
            subscribedTopics,
            Objects.requireNonNullElse(sessionTimeoutMs, ConfigMetadata.SESSION_TIMEOUT_MS.intDefault()),
            Objects.requireNonNullElse(heartbeatIntervalMs, ConfigMetadata.HEARTBEAT_INTERVAL_MS.intDefault()),
            Objects.requireNonNullElse(assignmentIntervalMs, ConfigMetadata.ASSIGNMENT_INTERVAL_MS.intDefault()),
            consumerDefaults == null ? ConsumerSettings.defaults() : consumerDefaults.toSettings(),
            consumers
        );
    }
}
