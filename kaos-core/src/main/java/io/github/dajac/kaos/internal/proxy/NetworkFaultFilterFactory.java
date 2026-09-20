package io.github.dajac.kaos.internal.proxy;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

import io.kroxylicious.kafka.common.message.RequestHeaderData;
import io.kroxylicious.kafka.common.protocol.ApiKeys;
import io.kroxylicious.kafka.common.protocol.ApiMessage;
import io.kroxylicious.proxy.filter.Filter;
import io.kroxylicious.proxy.filter.FilterContext;
import io.kroxylicious.proxy.filter.FilterFactory;
import io.kroxylicious.proxy.filter.FilterFactoryContext;
import io.kroxylicious.proxy.filter.RequestFilter;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.plugin.Plugin;
import io.kroxylicious.proxy.plugin.PluginConfigurationException;

/**
 * Creates the proxy filter that applies {@link NetworkFaults} to the requests of each client.
 *
 * <p>The factory has two states. The no-arg constructor makes an <em>unbound</em> factory: it
 * exists for the ServiceLoader, which the proxy's configuration parser uses to resolve the filter
 * type, and it cannot create filters. {@link #NetworkFaultFilterFactory(NetworkFaults)} makes a
 * <em>bound</em> factory, which {@link BoundPluginRegistry} hands to the proxy that owns those
 * faults. The faults therefore reach the filter as an instance, never through static state.
 */
@Plugin(configType = Void.class)
public final class NetworkFaultFilterFactory implements FilterFactory<Void, Void> {

    private final NetworkFaults faults;

    /** Creates an unbound factory. Only the ServiceLoader calls this. */
    public NetworkFaultFilterFactory() {
        this.faults = null;
    }

    /** Creates a factory whose filters apply {@code faults}. */
    public NetworkFaultFilterFactory(NetworkFaults faults) {
        this.faults = Objects.requireNonNull(faults, "faults");
    }

    @Override
    public Void initialize(FilterFactoryContext context, Void config) {
        if (faults == null) {
            throw new PluginConfigurationException(
                getClass().getSimpleName() + " is not bound to a NetworkFaults instance;"
                    + " the proxy must be started by ProxyLauncher");
        }
        return null;
    }

    @Override
    public Filter createFilter(FilterFactoryContext context, Void initializationData) {
        if (faults == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " is not bound");
        }
        return new NetworkFaultFilter(faults);
    }

    /**
     * Decides the fate of every request from the client id in its header. A connection is never
     * marked: a client's connections all carry the same id, so they fail and recover together,
     * and the decision follows the faults as they change.
     *
     * <p>A filter only acts while it handles a request, so it cannot close a connection at the
     * moment a fault is cleared. A connection with a dropped request stays unusable until the
     * client times out on it; see {@link NetworkFaults.Mode#BLACKHOLE}.
     */
    private static final class NetworkFaultFilter implements RequestFilter {

        private final NetworkFaults faults;

        private NetworkFaultFilter(NetworkFaults faults) {
            this.faults = faults;
        }

        @Override
        public CompletionStage<RequestFilterResult> onRequest(
                ApiKeys apiKey,
                short apiVersion,
                RequestHeaderData header,
                ApiMessage request,
                FilterContext context) {
            NetworkFaults.Mode mode = faults.modeOf(header.clientId());
            if (mode == null) {
                return context.forwardRequest(header, request);
            }
            return switch (mode) {
                case BLACKHOLE -> context.requestFilterResultBuilder().drop().completed();
                case RESET -> context.requestFilterResultBuilder().withCloseConnection().completed();
            };
        }
    }
}
