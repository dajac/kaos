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
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * The options of a consumer, set from a lambda. A value that is not set inherits from the
 * {@code consumerDefaults} of the group, or from {@link ConsumerSettings#defaults()} when the lambda
 * describes those defaults.
 */
public final class ConsumerOptions {
    private Optional<Protocol> protocol = Optional.empty();
    private Optional<String> assignor = Optional.empty();
    private Optional<Boolean> staticMember = Optional.empty();
    private Optional<String> rackId = Optional.empty();
    private OptionalInt pollTimeoutMs = OptionalInt.empty();
    private OptionalInt pollDelayMs = OptionalInt.empty();
    private OptionalInt maxPollIntervalMs = OptionalInt.empty();
    private OptionalInt sessionTimeoutMs = OptionalInt.empty();
    private OptionalInt heartbeatIntervalMs = OptionalInt.empty();

    ConsumerOptions() {}

    /** Runs the lambda on fresh options and returns what it set. */
    static ConsumerOverrides overrides(Consumer<ConsumerOptions> options) {
        Objects.requireNonNull(options, "options");
        var target = new ConsumerOptions();
        options.accept(target);
        return new ConsumerOverrides(
            target.protocol,
            target.assignor,
            target.staticMember,
            target.rackId,
            target.pollTimeoutMs,
            target.pollDelayMs,
            target.maxPollIntervalMs,
            target.sessionTimeoutMs,
            target.heartbeatIntervalMs
        );
    }

    /**
     * Runs the lambda on fresh options and returns what it set, for the {@code consumerDefaults} of
     * a group, which have no session timeout or heartbeat interval: the group sets its own.
     */
    static ConsumerOverrides defaults(Consumer<ConsumerOptions> options) {
        var defaults = overrides(options);
        if (defaults.sessionTimeoutMs().isPresent()) {
            throw new IllegalArgumentException(
                "consumerDefaults has no session timeout: set the session timeout of the group with "
                    + "GroupOptions.sessionTimeout"
            );
        }
        if (defaults.heartbeatIntervalMs().isPresent()) {
            throw new IllegalArgumentException(
                "consumerDefaults has no heartbeat interval: set the heartbeat interval of the group with "
                    + "GroupOptions.heartbeatInterval"
            );
        }
        return defaults;
    }

    /** The group protocol. */
    public ConsumerOptions protocol(Protocol protocol) {
        this.protocol = Optional.of(protocol);
        return this;
    }

    /**
     * The assignor name. The classic protocol accepts the names listed in {@link ConfigMetadata};
     * the consumer protocol accepts any name. The empty name selects the default of the protocol.
     */
    public ConsumerOptions assignor(String assignor) {
        this.assignor = Optional.of(assignor);
        return this;
    }

    /** Whether the consumer is a static member. */
    public ConsumerOptions staticMember(boolean staticMember) {
        this.staticMember = Optional.of(staticMember);
        return this;
    }

    /** The rack of the consumer. The empty id means no rack. */
    public ConsumerOptions rackId(String rackId) {
        this.rackId = Optional.of(rackId);
        return this;
    }

    /** The timeout given to {@code poll}. */
    public ConsumerOptions pollTimeout(Duration pollTimeout) {
        this.pollTimeoutMs = OptionalInt.of(Durations.toMillis(pollTimeout));
        return this;
    }

    /** The pause between two polls. */
    public ConsumerOptions pollDelay(Duration pollDelay) {
        this.pollDelayMs = OptionalInt.of(Durations.toMillis(pollDelay));
        return this;
    }

    /** {@code max.poll.interval.ms}. */
    public ConsumerOptions maxPollInterval(Duration maxPollInterval) {
        this.maxPollIntervalMs = OptionalInt.of(Durations.toMillis(maxPollInterval));
        return this;
    }

    /**
     * The {@code session.timeout.ms} of a classic consumer, over the session timeout of its group.
     * A consumer of the consumer protocol cannot set it; nor can the {@code consumerDefaults}.
     */
    public ConsumerOptions sessionTimeout(Duration sessionTimeout) {
        this.sessionTimeoutMs = OptionalInt.of(Durations.toMillis(sessionTimeout));
        return this;
    }

    /**
     * The {@code heartbeat.interval.ms} of a classic consumer, over the heartbeat interval of its
     * group. A consumer of the consumer protocol cannot set it; nor can the {@code consumerDefaults}.
     */
    public ConsumerOptions heartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatIntervalMs = OptionalInt.of(Durations.toMillis(heartbeatInterval));
        return this;
    }
}
