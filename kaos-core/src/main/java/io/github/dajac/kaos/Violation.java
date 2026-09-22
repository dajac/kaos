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
package io.github.dajac.kaos;

import java.util.Objects;

/**
 * One reason why a configuration is invalid.
 *
 * @param field the path of the offending value, such as {@code groups[0].sessionTimeoutMs}
 * @param message what is wrong with it
 */
public record Violation(String field, String message) {
    public Violation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
    }
}
