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
            target.maxPollIntervalMs
        );
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
}
