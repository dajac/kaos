package io.github.dajac.kaos;

import java.util.Objects;

/**
 * A complete set of consumer settings. It is used for the {@code consumerDefaults} of a group and
 * for the {@code effective} values of a consumer.
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
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(assignor, "assignor");
        Objects.requireNonNull(rackId, "rackId");
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
            ConfigMetadata.MAX_POLL_INTERVAL_MS.intDefault());
    }
}
