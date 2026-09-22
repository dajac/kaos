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
