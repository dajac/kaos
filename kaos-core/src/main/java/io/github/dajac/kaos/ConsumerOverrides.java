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
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The settings a consumer sets itself. An empty value inherits from the {@code consumerDefaults} of
 * the group. The components mirror {@link ConsumerSettings}. A {@code null} component is read as
 * empty, so that a request body can leave it out.
 */
public record ConsumerOverrides(
    Optional<Protocol> protocol,
    Optional<String> assignor,
    Optional<Boolean> staticMember,
    Optional<String> rackId,
    OptionalInt pollTimeoutMs,
    OptionalInt pollDelayMs,
    OptionalInt maxPollIntervalMs
) {
    private static final ConsumerOverrides NONE = new ConsumerOverrides(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        OptionalInt.empty(),
        OptionalInt.empty(),
        OptionalInt.empty()
    );

    public ConsumerOverrides {
        protocol = Objects.requireNonNullElse(protocol, Optional.empty());
        assignor = Objects.requireNonNullElse(assignor, Optional.empty());
        staticMember = Objects.requireNonNullElse(staticMember, Optional.empty());
        rackId = Objects.requireNonNullElse(rackId, Optional.empty());
        pollTimeoutMs = Objects.requireNonNullElse(pollTimeoutMs, OptionalInt.empty());
        pollDelayMs = Objects.requireNonNullElse(pollDelayMs, OptionalInt.empty());
        maxPollIntervalMs = Objects.requireNonNullElse(maxPollIntervalMs, OptionalInt.empty());
    }

    /** No override: the consumer inherits every setting. */
    public static ConsumerOverrides none() {
        return NONE;
    }
}
