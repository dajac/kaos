package io.github.dajac.kaos.internal.config;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.Protocol;

import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.RangeAssignor;
import org.apache.kafka.clients.consumer.RoundRobinAssignor;
import org.apache.kafka.clients.consumer.StickyAssignor;

import java.util.Map;
import java.util.Optional;

/**
 * Maps the assignor names of {@link ConfigMetadata} to what the Kafka clients need. The names and
 * the rule of which protocol accepts which name live in {@link ConfigMetadata}; this class only adds
 * the client-side classes of the classic assignors.
 */
public final class Assignors {
    private static final Map<String, String> CLASSIC_CLASSES = Map.of(
        "range", RangeAssignor.class.getName(),
        "roundrobin", RoundRobinAssignor.class.getName(),
        "sticky", StickyAssignor.class.getName(),
        "cooperative-sticky", CooperativeStickyAssignor.class.getName());

    private Assignors() {
    }

    /** Whether the assignor name can be used with the protocol. */
    public static boolean isValid(Protocol protocol, String assignor) {
        return ConfigMetadata.get().assignors(protocol).accepts(assignor);
    }

    /**
     * The value of {@code partition.assignment.strategy} for a classic assignor name. It is empty
     * for the empty name, which keeps the default of the client, and for an unknown name.
     */
    public static Optional<String> classicAssignorClass(String assignor) {
        return Optional.ofNullable(CLASSIC_CLASSES.get(assignor));
    }

    /**
     * The value of {@code group.remote.assignor} for a consumer protocol assignor name. It is empty
     * for the empty name, which keeps the default of the broker.
     */
    public static Optional<String> remoteAssignor(String assignor) {
        return assignor.isEmpty() ? Optional.empty() : Optional.of(assignor);
    }
}
