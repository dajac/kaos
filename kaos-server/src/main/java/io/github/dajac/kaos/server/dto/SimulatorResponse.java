package io.github.dajac.kaos.server.dto;

import java.util.List;

import io.github.dajac.kaos.Phase;
import io.github.dajac.kaos.SimulatorSpec;

/**
 * The body of {@code GET /api/v1/simulator}: the status and the spec of the simulation, with the
 * overrides and the effective settings of each consumer.
 *
 * @param phase see {@link StatusResponse#phase()}
 * @param spec the declared simulation, with the assigned ids
 * @param lastError see {@link StatusResponse#lastError()}
 * @param warnings see {@link StatusResponse#warnings()}
 */
public record SimulatorResponse(Phase phase, SimulatorSpec spec, String lastError, List<String> warnings) {

    public SimulatorResponse {
        warnings = List.copyOf(warnings);
    }
}
