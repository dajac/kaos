package io.github.dajac.kaos;

import java.time.Duration;

/**
 * The autopilot of a group. While it is on, it waits for a random time between two intervals and
 * then flips the subscription of one random consumer of the group: a consumer that is in the group
 * leaves it and one that is out joins it. Every method is thread-safe.
 */
public interface Autopilot {
    /**
     * Turns the autopilot on, or gives new intervals to an autopilot that is on.
     *
     * @param minInterval the shortest pause between two flips, at least 1 ms
     * @param maxInterval the longest pause between two flips, at least {@code minInterval}
     * @throws IllegalArgumentException if the intervals are invalid
     * @throws SimulationStateException if the group does not run
     */
    void start(Duration minInterval, Duration maxInterval);

    /** Turns the autopilot off. The intervals are kept. */
    void stop();

    /** Whether the autopilot is on. */
    boolean enabled();
}
