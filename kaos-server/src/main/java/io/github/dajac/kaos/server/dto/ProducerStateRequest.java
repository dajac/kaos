package io.github.dajac.kaos.server.dto;

/**
 * The body of {@code PUT /api/v1/simulator/producers/{id}/state}.
 *
 * @param paused whether the producer is paused; required
 */
public record ProducerStateRequest(Boolean paused) {}
