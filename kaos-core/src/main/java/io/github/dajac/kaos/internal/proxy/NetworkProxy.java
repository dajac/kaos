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

/**
 * A running proxy in front of the cluster. Clients that connect to {@link #bootstrapServers()} are
 * subject to the {@link #faults()} of this proxy; clients that connect to the cluster directly are
 * not.
 *
 * <p>The interface hides the topology. One proxy for all clients is enough for faults that match
 * on the client id; a proxy per group or a port per client would be other implementations.
 */
public interface NetworkProxy extends AutoCloseable {

    /** The {@code bootstrap.servers} value that routes a client through this proxy. */
    String bootstrapServers();

    /** The faults this proxy applies. A change takes effect on the next request of a client. */
    NetworkFaults faults();

    /**
     * Stops the proxy and releases its ports and threads. The call blocks for a bounded time and
     * is idempotent.
     */
    @Override
    void close();
}
