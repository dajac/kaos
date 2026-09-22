package io.github.dajac.kaos.internal.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.Protocol;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.RangeAssignor;
import org.apache.kafka.clients.consumer.RoundRobinAssignor;
import org.apache.kafka.clients.consumer.StickyAssignor;

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
        "cooperative-sticky", CooperativeStickyAssignor.class.getName()
    );

    private static final List<String> CLASSIC_DEFAULTS = List.of("range", "cooperative-sticky");

    private Assignors() {}

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
     * The assignors of a classic consumer that keeps the default of the client, which lists
     * {@code range} and then {@code cooperative-sticky}.
     */
    public static List<String> classicDefaults() {
        return CLASSIC_DEFAULTS;
    }

    /**
     * The assignors that a classic consumer with the given assignor name offers to its group: the
     * named one, or {@link #classicDefaults()} for the empty name.
     */
    public static List<String> supportedClassicAssignors(String assignor) {
        return assignor.isEmpty() ? CLASSIC_DEFAULTS : List.of(assignor);
    }

    /**
     * Whether a classic assignor name selects the eager rebalance protocol, in which a consumer
     * gives up all its partitions before it rejoins. Only {@code cooperative-sticky} is cooperative.
     * The empty name is eager, because the default of the client lists {@code range} first.
     */
    public static boolean isEagerClassic(String assignor) {
        return !"cooperative-sticky".equals(assignor);
    }

    /**
     * The value of {@code group.remote.assignor} for a consumer protocol assignor name. It is empty
     * for the empty name, which keeps the default of the broker.
     */
    public static Optional<String> remoteAssignor(String assignor) {
        return assignor.isEmpty() ? Optional.empty() : Optional.of(assignor);
    }
}
