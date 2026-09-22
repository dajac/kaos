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
import java.util.Objects;
import java.util.function.Consumer;

import io.github.dajac.kaos.internal.config.EffectiveConfigResolver;

/**
 * The description of a consumer group.
 *
 * <p>The {@code effective} settings of each consumer are recomputed from {@code consumerDefaults}
 * and the consumer's {@code overrides}, so they are always consistent with the group.
 *
 * <p>A request body can leave values out: a {@code null} id or list is read as empty, and a
 * {@code null} {@code consumerDefaults} as {@link ConsumerSettings#defaults()}. The validation
 * rejects the empty id and the empty subscription.
 *
 * @param id the group id
 * @param subscribedTopics the names of the topics the consumers subscribe to
 * @param sessionTimeoutMs the group config {@code consumer.session.timeout.ms}
 * @param heartbeatIntervalMs the group config {@code consumer.heartbeat.interval.ms}
 * @param assignmentIntervalMs the group config {@code consumer.assignment.interval.ms}
 * @param consumerDefaults the settings the consumers inherit
 * @param consumers the consumers
 */
public record GroupSpec(
    String id,
    List<String> subscribedTopics,
    int sessionTimeoutMs,
    int heartbeatIntervalMs,
    int assignmentIntervalMs,
    ConsumerSettings consumerDefaults,
    List<ConsumerSpec> consumers
) {
    public GroupSpec {
        id = Objects.requireNonNullElse(id, "");
        consumerDefaults = Objects.requireNonNullElseGet(consumerDefaults, ConsumerSettings::defaults);
        subscribedTopics = subscribedTopics == null ? List.of() : List.copyOf(subscribedTopics);
        consumers = EffectiveConfigResolver.resolve(consumerDefaults, consumers == null ? List.of() : consumers);
    }

    /** A group described by an options lambda. */
    public static GroupSpec of(String id, Consumer<GroupOptions> options) {
        return GroupOptions.spec(id, options);
    }
}
