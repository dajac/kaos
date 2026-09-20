package io.github.dajac.kaos.internal.proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.kroxylicious.proxy.KafkaProxy;
import io.kroxylicious.proxy.config.ConfigParser;
import io.kroxylicious.proxy.internal.config.Features;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts a {@link NetworkProxy} in front of a cluster.
 *
 * <p>The proxy listens on a contiguous block of local ports: the bootstrap port, then one port
 * per broker, in ascending node id order. The launcher looks for the first free block at or after
 * the requested port, so a busy port, or another proxy in the same JVM, moves the proxy further
 * up instead of failing the launch.
 *
 * <p>This is the only class that touches the proxy runtime's non-API surface
 * ({@code io.kroxylicious.proxy.internal.config.Features}, which the {@link KafkaProxy}
 * constructor requires).
 */
public final class ProxyLauncher {

    /** The proxy port used when the caller has no preference. */
    public static final int DEFAULT_PORT = 19092;

    private static final Logger LOG = LoggerFactory.getLogger(ProxyLauncher.class);

    private static final String HOST = "localhost";
    private static final String CLUSTER_NAME = "target";
    private static final String FILTER_NAME = "network-faults";
    private static final int MAX_PORT = 65535;

    /** How many times a block that was free when probed may turn out taken at startup. */
    private static final int MAX_ATTEMPTS = 10;

    /**
     * How long the proxy waits for the in-flight requests of a connection before it closes it,
     * both when the filter closes a connection and at shutdown. The request that triggers a
     * {@code RESET} and the requests dropped by a {@code BLACKHOLE} never complete, so the wait
     * is kept short.
     */
    private static final String DRAIN_TIMEOUT = "100ms";

    /** Shutdown bounds of the proxy's event loops. They bound {@link NetworkProxy#close()}. */
    private static final String SHUTDOWN_QUIET_PERIOD = "0ms";
    private static final String SHUTDOWN_TIMEOUT = "2s";

    private final String bootstrapServers;
    private final int requestedPort;
    private final Duration timeout;
    private final IntConsumer afterProbe;

    /**
     * @param bootstrapServers the cluster the proxy forwards to
     * @param requestedPort the preferred bootstrap port of the proxy
     * @param timeout the bound on discovering the brokers of the cluster
     */
    public ProxyLauncher(String bootstrapServers, int requestedPort, Duration timeout) {
        this(bootstrapServers, requestedPort, timeout, port -> {});
    }

    /**
     * @param afterProbe called with the bootstrap port of each block found free, before the proxy
     *     binds it. Tests use it to take a port of the block at that point.
     */
    ProxyLauncher(String bootstrapServers, int requestedPort, Duration timeout, IntConsumer afterProbe) {
        this.bootstrapServers = Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        if (requestedPort < 1 || requestedPort > MAX_PORT) {
            throw new IllegalArgumentException("Invalid proxy port: " + requestedPort);
        }
        this.requestedPort = requestedPort;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.afterProbe = Objects.requireNonNull(afterProbe, "afterProbe");
    }

    /**
     * Starts a proxy that applies {@code faults} and returns once it accepts connections.
     *
     * @param admin an admin client of the target cluster, used to discover the broker ids. It
     *     stays owned by the caller.
     * @throws IllegalStateException if the brokers cannot be discovered, if no free port block is
     *     found or if the proxy fails to start. Nothing is left running in that case.
     */
    public NetworkProxy launch(Admin admin, NetworkFaults faults) {
        Objects.requireNonNull(admin, "admin");
        Objects.requireNonNull(faults, "faults");

        var nodeIds = discoverNodeIds(admin);
        var blockSize = 1 + nodeIds.size();
        var parser = new ConfigParser();
        var registry = new BoundPluginRegistry(parser, new NetworkFaultFilterFactory(faults));

        var port = requestedPort;
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            port = findFreeBlock(port, blockSize);
            afterProbe.accept(port);
            var configuration = parser.parseConfiguration(toYaml(configuration(bootstrapServers, port, nodeIds)));
            var proxy = new KafkaProxy(registry, configuration, Features.defaultFeatures());
            try {
                // startup() binds the ports before it returns. The future it returns completes
                // when the proxy stops, so it is not awaited.
                proxy.startup();
            } catch (RuntimeException e) {
                // Releases whatever the failed startup bound or started.
                proxy.close();
                // The bind failure of the proxy depends on its transport, so the exception is
                // not inspected. The block is probed again instead: if one of its ports is now
                // busy, someone took it between the probe and the bind.
                var busy = firstBusyPort(port, blockSize);
                if (busy < 0) {
                    throw new IllegalStateException("The proxy failed to start on port " + port, e);
                }
                LOG.debug("Proxy port {} was taken during startup, trying further", busy);
                lastFailure = e;
                port = busy + 1;
                continue;
            }
            if (port != requestedPort) {
                LOG.info("Proxy port {} is busy, using {} instead", requestedPort, port);
            }
            return new RunningProxy(proxy, HOST + ":" + port, faults);
        }
        throw new IllegalStateException(
            "The proxy ports were taken during startup " + MAX_ATTEMPTS + " times in a row, from port " + requestedPort,
            lastFailure
        );
    }

    private TreeSet<Integer> discoverNodeIds(Admin admin) {
        Collection<Node> nodes;
        try {
            nodes = admin.describeCluster().nodes().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while describing the cluster " + bootstrapServers, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not describe the cluster " + bootstrapServers, e);
        }
        var nodeIds = new TreeSet<Integer>();
        nodes.forEach(node -> nodeIds.add(node.id()));
        if (nodeIds.isEmpty()) {
            throw new IllegalStateException("The cluster " + bootstrapServers + " reports no brokers");
        }
        return nodeIds;
    }

    /**
     * Builds the proxy configuration as a plain map, which is then rendered to YAML and parsed by
     * the proxy's own parser. The node id ranges are explicit because the proxy otherwise assumes
     * the node ids 0 to 2.
     */
    static Map<String, Object> configuration(String bootstrapServers, int proxyPort, TreeSet<Integer> nodeIds) {
        var shutdown = new LinkedHashMap<String, Object>();
        shutdown.put("shutdownQuietPeriod", SHUTDOWN_QUIET_PERIOD);
        shutdown.put("shutdownTimeout", SHUTDOWN_TIMEOUT);
        var proxyNetwork = new LinkedHashMap<String, Object>(shutdown);
        var managementNetwork = new LinkedHashMap<String, Object>(shutdown);
        // The management endpoint is not enabled, so its event loop stays idle.
        managementNetwork.put("workerThreadCount", 1);

        var portIdentifiesNode = new LinkedHashMap<String, Object>();
        portIdentifiesNode.put("bootstrapAddress", HOST + ":" + proxyPort);
        portIdentifiesNode.put("nodeIdRanges", nodeIdRanges(nodeIds));

        var virtualCluster = new LinkedHashMap<String, Object>();
        virtualCluster.put("name", "kaos");
        virtualCluster.put("target", Map.of("cluster", CLUSTER_NAME));
        virtualCluster.put("gateways", List.of(Map.of("name", "default", "portIdentifiesNode", portIdentifiesNode)));
        virtualCluster.put("drainTimeout", DRAIN_TIMEOUT);

        var configuration = new LinkedHashMap<String, Object>();
        configuration.put("clusterDefinitions", List.of(Map.of("name", CLUSTER_NAME, "bootstrapServers", bootstrapServers)));
        configuration.put("filterDefinitions", List.of(Map.of("name", FILTER_NAME, "type", NetworkFaultFilterFactory.class.getName())));
        configuration.put("defaultFilters", List.of(FILTER_NAME));
        configuration.put("virtualClusters", List.of(virtualCluster));
        configuration.put("network", Map.of("proxy", proxyNetwork, "management", managementNetwork));
        return configuration;
    }

    /** Groups the node ids into inclusive ranges of consecutive ids. */
    static List<Map<String, Object>> nodeIdRanges(TreeSet<Integer> nodeIds) {
        var ranges = new ArrayList<Map<String, Object>>();
        Integer start = null;
        Integer end = null;
        for (var nodeId : nodeIds) {
            if (start == null) {
                start = nodeId;
            } else if (nodeId != end + 1) {
                ranges.add(range(ranges.size(), start, end));
                start = nodeId;
            }
            end = nodeId;
        }
        if (start != null) {
            ranges.add(range(ranges.size(), start, end));
        }
        return ranges;
    }

    private static Map<String, Object> range(int index, int start, int end) {
        var range = new LinkedHashMap<String, Object>();
        range.put("name", "brokers-" + index);
        range.put("start", start);
        range.put("end", end);
        return range;
    }

    static String toYaml(Map<String, Object> configuration) {
        try {
            return new YAMLMapper().writeValueAsString(configuration);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not render the proxy configuration", e);
        }
    }

    /**
     * Returns the first port at or after {@code from} where {@code size} consecutive ports are
     * free. When a port of a candidate block is busy, the search resumes right after it.
     */
    static int findFreeBlock(int from, int size) {
        var base = from;
        while (base + size - 1 <= MAX_PORT) {
            var busy = firstBusyPort(base, size);
            if (busy < 0) {
                return base;
            }
            base = busy + 1;
        }
        throw new IllegalStateException("No free block of " + size + " ports found from port " + from);
    }

    private static int firstBusyPort(int base, int size) {
        for (int port = base; port < base + size; port++) {
            if (!isFree(port)) {
                return port;
            }
        }
        return -1;
    }

    /**
     * Probes a port the way the proxy binds it: on the wildcard address, with address reuse, so a
     * port that a closed proxy just released counts as free. With address reuse, some systems let
     * the wildcard bind succeed next to a listener bound to the loopback address only, which
     * would then receive the clients' connections; the second bind detects that listener.
     */
    static boolean isFree(int port) {
        return canBind(new InetSocketAddress(port)) && canBind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    }

    private static boolean canBind(InetSocketAddress address) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(address);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static final class RunningProxy implements NetworkProxy {

        private final KafkaProxy proxy;
        private final String bootstrapServers;
        private final NetworkFaults faults;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RunningProxy(KafkaProxy proxy, String bootstrapServers, NetworkFaults faults) {
            this.proxy = proxy;
            this.bootstrapServers = bootstrapServers;
            this.faults = faults;
        }

        @Override
        public String bootstrapServers() {
            return bootstrapServers;
        }

        @Override
        public NetworkFaults faults() {
            return faults;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                // Blocks until the ports are unbound and the event loops have stopped, within the
                // shutdown bounds of the configuration. Failures are logged by the proxy.
                proxy.close();
            }
        }
    }
}
