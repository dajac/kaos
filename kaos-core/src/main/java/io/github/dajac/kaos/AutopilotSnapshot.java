package io.github.dajac.kaos;

/**
 * The live view of the autopilot of a group. The autopilot flips the subscription of one random
 * consumer after a random pause between the two intervals. The intervals are the last ones it was
 * given, and are kept while it is off.
 *
 * @param enabled whether the autopilot is on
 * @param minIntervalMs the shortest pause between two flips
 * @param maxIntervalMs the longest pause between two flips
 */
public record AutopilotSnapshot(boolean enabled, int minIntervalMs, int maxIntervalMs) {}
