package io.github.dajac.kaos;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The settings a consumer sets itself. An empty value inherits from the {@code consumerDefaults} of
 * the group. The components mirror {@link ConsumerSettings}.
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
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(assignor, "assignor");
        Objects.requireNonNull(staticMember, "staticMember");
        Objects.requireNonNull(rackId, "rackId");
        Objects.requireNonNull(pollTimeoutMs, "pollTimeoutMs");
        Objects.requireNonNull(pollDelayMs, "pollDelayMs");
        Objects.requireNonNull(maxPollIntervalMs, "maxPollIntervalMs");
    }

    /** No override: the consumer inherits every setting. */
    public static ConsumerOverrides none() {
        return NONE;
    }
}
