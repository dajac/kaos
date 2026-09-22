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

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.dajac.kaos.Violation;

/**
 * An RFC 9457 problem detail, served as {@code application/problem+json}.
 *
 * @param type always {@code about:blank}: the status code says what kind of problem it is
 * @param title the reason phrase of the status
 * @param status the HTTP status
 * @param detail a human-readable explanation of this occurrence
 * @param violations the invalid fields of the request, for a 400; left out when empty
 */
public record Problem(
    String type,
    String title,
    int status,
    String detail,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Violation> violations
) {

    public Problem {
        violations = List.copyOf(violations);
    }
}
