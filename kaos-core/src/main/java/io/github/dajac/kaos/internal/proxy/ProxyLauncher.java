package io.github.dajac.kaos.internal.proxy;

import java.io.IOException;
import java.net.BindException;
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.kroxylicious.proxy.KafkaProxy;
import io.kroxylicious.proxy.config.ConfigParser;
import io.kroxylicious.proxy.config.Configuration;
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

    /** How many candidate blocks are tried before the launch fails. */
    private static final int MAX_ATTEMPTS = 100;

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

    /**
     * @param bootstrapServers the cluster the proxy forwards to
     * @param requestedPort the preferred bootstrap port of the proxy
     * @param timeout the bound on discovering the brokers of the cluster
     */
    public ProxyLauncher(String bootstrapServers, int requestedPort, Duration timeout) {
        this.bootstrapServers = Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        if (requestedPort < 1 || requestedPort > MAX_PORT) {
            throw new IllegalArgumentException("Invalid proxy port: " + requestedPort);
        }
        this.requestedPort = requestedPort;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
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

        TreeSet<Integer> nodeIds = discoverNodeIds(admin);
        int blockSize = 1 + nodeIds.size();
        ConfigParser parser = new ConfigParser();
        BoundPluginRegistry registry = new BoundPluginRegistry(parser, new NetworkFaultFilterFactory(faults));

        int port = requestedPort;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            port = findFreeBlock(port, blockSize);
            Configuration configuration = parser.parseConfiguration(toYaml(configuration(bootstrapServers, port, nodeIds)));
            KafkaProxy proxy = new KafkaProxy(registry, configuration, Features.defaultFeatures());
            try {
                // startup() binds the ports before it returns. The future it returns completes
                // when the proxy stops, so it is not awaited.
                proxy.startup();
            } catch (RuntimeException e) {
                proxy.close();
                if (!causedByBind(e)) {
                    throw new IllegalStateException("The proxy failed to start on port " + port, e);
                }
                // Another process took a port of the block between the probe and the bind.
                LOG.debug("Proxy ports {}-{} were taken during startup, trying further", port, port + blockSize - 1);
                port++;
                continue;
            }
            if (port != requestedPort) {
                LOG.info("Proxy port {} is busy, using {} instead", requestedPort, port);
            }
            return new RunningProxy(proxy, HOST + ":" + port, faults);
        }
        throw new IllegalStateException("No free block of " + blockSize + " ports found from port " + requestedPort);
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
        TreeSet<Integer> nodeIds = new TreeSet<>();
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
        Map<String, Object> shutdown = new LinkedHashMap<>();
        shutdown.put("shutdownQuietPeriod", SHUTDOWN_QUIET_PERIOD);
        shutdown.put("shutdownTimeout", SHUTDOWN_TIMEOUT);
        Map<String, Object> proxyNetwork = new LinkedHashMap<>(shutdown);
        Map<String, Object> managementNetwork = new LinkedHashMap<>(shutdown);
        // The management endpoint is not enabled, so its event loop stays idle.
        managementNetwork.put("workerThreadCount", 1);

        Map<String, Object> portIdentifiesNode = new LinkedHashMap<>();
        portIdentifiesNode.put("bootstrapAddress", HOST + ":" + proxyPort);
        portIdentifiesNode.put("nodeIdRanges", nodeIdRanges(nodeIds));

        Map<String, Object> virtualCluster = new LinkedHashMap<>();
        virtualCluster.put("name", "kaos");
        virtualCluster.put("target", Map.of("cluster", CLUSTER_NAME));
        virtualCluster.put("gateways", List.of(Map.of("name", "default", "portIdentifiesNode", portIdentifiesNode)));
        virtualCluster.put("drainTimeout", DRAIN_TIMEOUT);

        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("clusterDefinitions", List.of(Map.of("name", CLUSTER_NAME, "bootstrapServers", bootstrapServers)));
        configuration.put("filterDefinitions", List.of(Map.of("name", FILTER_NAME, "type", NetworkFaultFilterFactory.class.getName())));
        configuration.put("defaultFilters", List.of(FILTER_NAME));
        configuration.put("virtualClusters", List.of(virtualCluster));
        configuration.put("network", Map.of("proxy", proxyNetwork, "management", managementNetwork));
        return configuration;
    }

    /** Groups the node ids into inclusive ranges of consecutive ids. */
    static List<Map<String, Object>> nodeIdRanges(TreeSet<Integer> nodeIds) {
        List<Map<String, Object>> ranges = new ArrayList<>();
        Integer start = null;
        Integer end = null;
        for (int nodeId : nodeIds) {
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
        Map<String, Object> range = new LinkedHashMap<>();
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
        int base = from;
        while (base + size - 1 <= MAX_PORT) {
            int busy = firstBusyPort(base, size);
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
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(address);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean causedByBind(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof BindException) {
                return true;
            }
        }
        return false;
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
