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
        OptionalInt.empty());

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
