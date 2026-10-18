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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import io.github.dajac.kaos.internal.config.EffectiveConfigResolver;

/**
 * The options of a consumer group, set from a lambda. The order of the calls does not matter: the
 * consumers inherit from the final {@code consumerDefaults}.
 */
public final class GroupOptions {
    private final List<String> subscribedTopics = new ArrayList<>();
    private int sessionTimeoutMs = ConfigMetadata.SESSION_TIMEOUT_MS.intDefault();
    private int heartbeatIntervalMs = ConfigMetadata.HEARTBEAT_INTERVAL_MS.intDefault();
    private int assignmentIntervalMs = ConfigMetadata.ASSIGNMENT_INTERVAL_MS.intDefault();
    private ConsumerOverrides consumerDefaults = ConsumerOverrides.none();
    private final List<ConsumerSpec> consumers = new ArrayList<>();

    GroupOptions() {}

    /** Runs the lambda on fresh options and returns the spec it describes. */
    static GroupSpec spec(String id, Consumer<GroupOptions> options) {
        Objects.requireNonNull(options, "options");
        var target = new GroupOptions();
        options.accept(target);
        return new GroupSpec(
            id,
            target.subscribedTopics,
            target.sessionTimeoutMs,
            target.heartbeatIntervalMs,
            target.assignmentIntervalMs,
            EffectiveConfigResolver.resolve(ConsumerSettings.defaults(), target.consumerDefaults),
            target.consumers
        );
    }

    /** Adds topics the consumers subscribe to, by name. */
    public GroupOptions subscribe(String... topics) {
        for (var topic : topics) {
            subscribedTopics.add(Objects.requireNonNull(topic, "topic"));
        }
        return this;
    }

    /** Adds topics the consumers subscribe to. */
    public GroupOptions subscribe(Topic... topics) {
        for (var topic : topics) {
            subscribedTopics.add(Objects.requireNonNull(topic, "topic").name());
        }
        return this;
    }

    /** The session timeout of the group, for the consumers of both protocols; see {@link GroupSpec}. */
    public GroupOptions sessionTimeout(Duration sessionTimeout) {
        this.sessionTimeoutMs = Durations.toMillis(sessionTimeout);
        return this;
    }

    /**
     * The heartbeat interval of the group, for the consumers of both protocols; see {@link GroupSpec}.
     */
    public GroupOptions heartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatIntervalMs = Durations.toMillis(heartbeatInterval);
        return this;
    }

    /** The assignment interval of the group. */
    public GroupOptions assignmentInterval(Duration assignmentInterval) {
        this.assignmentIntervalMs = Durations.toMillis(assignmentInterval);
        return this;
    }

    /**
     * The settings the consumers inherit. What the lambda does not set keeps the default of
     * {@link ConfigMetadata}. A later call replaces an earlier one.
     */
    public GroupOptions consumerDefaults(Consumer<ConsumerOptions> defaults) {
        this.consumerDefaults = ConsumerOptions.overrides(defaults);
        return this;
    }

    /** Adds one consumer with its own overrides. */
    public GroupOptions consumer(Consumer<ConsumerOptions> overrides) {
        consumers.add(ConsumerSpec.of(overrides));
        return this;
    }

    /** Adds {@code count} consumers that inherit every setting. */
    public GroupOptions consumers(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative, got " + count);
        }
        for (int i = 0; i < count; i++) {
            consumers.add(ConsumerSpec.inheriting());
        }
        return this;
    }
}
