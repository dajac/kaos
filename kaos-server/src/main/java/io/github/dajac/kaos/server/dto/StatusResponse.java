package io.github.dajac.kaos.server.dto;

import java.util.List;

import io.github.dajac.kaos.Phase;

/**
 * The status of the simulation: the data of the SSE {@code status} event and the body of the 202
 * responses.
 *
 * @param phase the phase of the simulator; {@code STARTING} or {@code STOPPING} as soon as a start or
 *     a stop is accepted
 * @param lastError the message of the last failed operation, empty otherwise
 * @param warnings what the groups could not apply at their start and went on without
 */
public record StatusResponse(Phase phase, String lastError, List<String> warnings) {

    public StatusResponse {
        warnings = List.copyOf(warnings);
    }
}
