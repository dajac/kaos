package io.github.dajac.kaos;

import java.util.Locale;

/** The group protocol a simulated consumer speaks. */
public enum Protocol {
    /** The consumer rebalance protocol of KIP-848. */
    CONSUMER,
    /** The classic rebalance protocol. */
    CLASSIC;

    /** The lower-case identifier used in the HTTP API and in {@code group.protocol}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the protocol with the given identifier, ignoring case.
     *
     * @throws IllegalArgumentException if no protocol has this identifier
     */
    public static Protocol of(String id) {
        for (Protocol protocol : values()) {
            if (protocol.id().equalsIgnoreCase(id)) {
                return protocol;
            }
        }
        throw new IllegalArgumentException("Unknown protocol: " + id);
    }
}
