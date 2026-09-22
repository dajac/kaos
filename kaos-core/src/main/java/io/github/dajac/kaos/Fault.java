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

import java.util.List;

/**
 * A disturbance that can be injected into a simulated consumer. Faults are values: two faults of
 * the same kind are equal.
 */
public sealed interface Fault permits Fault.Freeze, Fault.NetworkIsolation, Fault.ConnectionReset {

    /** The identifier used in the HTTP API and in {@link ConfigMetadata#faults()}. */
    String id();

    /** The consumer stops polling. The proxy is not involved. */
    static Fault freeze() {
        return new Freeze();
    }

    /** The proxy silently drops the requests of the consumer, like a network partition. */
    static Fault networkIsolation() {
        return new NetworkIsolation();
    }

    /** The proxy closes the connections of the consumer instead of forwarding its requests. */
    static Fault connectionReset() {
        return new ConnectionReset();
    }

    /** All the faults, in a stable order. */
    static List<Fault> all() {
        return List.of(freeze(), networkIsolation(), connectionReset());
    }

    /**
     * Returns the fault with the given identifier.
     *
     * @throws IllegalArgumentException if no fault has this identifier
     */
    static Fault of(String id) {
        return all().stream()
            .filter(fault -> fault.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown fault: " + id));
    }

    /** See {@link Fault#freeze()}. */
    record Freeze() implements Fault {
        @Override
        public String id() {
            return "freeze";
        }
    }

    /** See {@link Fault#networkIsolation()}. */
    record NetworkIsolation() implements Fault {
        @Override
        public String id() {
            return "network-isolation";
        }
    }

    /** See {@link Fault#connectionReset()}. */
    record ConnectionReset() implements Fault {
        @Override
        public String id() {
            return "connection-reset";
        }
    }
}
