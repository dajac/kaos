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

import java.util.Arrays;
import java.util.Locale;

/** The group protocol a simulated consumer speaks. */
public enum Protocol {
    /** The consumer rebalance protocol of KIP-848. */
    CONSUMER,
    /** The classic rebalance protocol. */
    CLASSIC;

    /** The lower-case identifier used in the HTTP API and in {@code group.protocol}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the protocol with the given identifier, ignoring case.
     *
     * @throws IllegalArgumentException if no protocol has this identifier
     */
    public static Protocol of(String id) {
        return Arrays.stream(values())
            .filter(protocol -> protocol.id().equalsIgnoreCase(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown protocol: " + id));
    }
}
