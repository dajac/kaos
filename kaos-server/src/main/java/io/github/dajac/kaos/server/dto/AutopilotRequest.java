package io.github.dajac.kaos.server.dto;

/**
 * The body of {@code PUT /api/v1/simulator/groups/{groupId}/autopilot}. It has the shape of the
 * {@code autopilot} of a group snapshot.
 *
 * @param enabled whether the autopilot is on; required
 * @param minIntervalMs the shortest interval between two flips, or null to keep the current one
 * @param maxIntervalMs the longest interval between two flips, or null to keep the current one
 */
public record AutopilotRequest(Boolean enabled, Integer minIntervalMs, Integer maxIntervalMs) {}
