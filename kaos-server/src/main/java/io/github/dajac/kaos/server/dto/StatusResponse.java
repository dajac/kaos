/*
 * Copyright 2026 The Kaos Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
