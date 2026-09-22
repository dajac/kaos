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
package io.github.dajac.kaos.internal.proxy;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The network faults of one proxy, keyed by Kafka {@code client.id}.
 *
 * <p>The proxy filter reads it on every request, from the proxy's I/O threads, while other threads
 * set and clear faults. All methods are thread-safe. Setting a fault applies to the next request
 * of the client, on all its connections. Clearing one forwards the next request again, but see
 * {@link Mode#BLACKHOLE} for when the client actually recovers.
 */
public final class NetworkFaults {

    /** What the proxy does with the requests of a faulted client. */
    public enum Mode {
        /**
         * Drops the requests silently, like a network partition. The client sees timeouts.
         *
         * <p>A connection that had a request dropped does not heal when the fault is cleared.
         * The proxy delivers responses in request order, so the responses to later requests on
         * that connection wait behind the dropped request, which is never answered. The client
         * recovers when it gives up on the dropped request and reconnects, that is within its
         * {@code request.timeout.ms} after the fault is cleared, or earlier if it closes the
         * connection itself, as the classic coordinator does on a session timeout.
         */
        BLACKHOLE,
        /**
         * Closes the client connection instead of forwarding. The client sees disconnects, and
         * recovers on its first reconnection after the fault is cleared.
         */
        RESET
    }

    private final ConcurrentHashMap<String, Mode> modes = new ConcurrentHashMap<>();

    /** Applies {@code mode} to the client, replacing its current mode if it has one. */
    public void set(String clientId, Mode mode) {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(mode, "mode");
        modes.put(clientId, mode);
    }

    /** Removes the fault of the client. Does nothing if the client has none. */
    public void clear(String clientId) {
        Objects.requireNonNull(clientId, "clientId");
        modes.remove(clientId);
    }

    /** Removes the faults of all clients. */
    public void clearAll() {
        modes.clear();
    }

    /**
     * Returns the mode applied to the client, or {@code null} if its requests are forwarded. A
     * request may carry no client id; {@code null} never has a fault.
     */
    public Mode modeOf(String clientId) {
        return clientId == null ? null : modes.get(clientId);
    }

    /** Returns an immutable copy of the current faults. */
    public Map<String, Mode> snapshot() {
        return Map.copyOf(modes);
    }
}
