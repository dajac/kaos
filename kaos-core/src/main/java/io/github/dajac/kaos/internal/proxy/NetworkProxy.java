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
