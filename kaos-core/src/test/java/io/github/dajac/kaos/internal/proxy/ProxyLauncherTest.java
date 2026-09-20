package io.github.dajac.kaos.internal.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import io.kroxylicious.proxy.config.ConfigParser;
import io.kroxylicious.proxy.config.NamedRange;
import io.kroxylicious.proxy.config.PortIdentifiesNodeIdentificationStrategy;
import org.junit.jupiter.api.Test;

class ProxyLauncherTest {

    @Test
    void groupsNodeIdsIntoRangesOfConsecutiveIds() {
        assertThat(ProxyLauncher.nodeIdRanges(new TreeSet<>(List.of(1))))
            .containsExactly(Map.of("name", "brokers-0", "start", 1, "end", 1));
        assertThat(ProxyLauncher.nodeIdRanges(new TreeSet<>(List.of(7, 1, 3, 2, 8, 100))))
            .containsExactly(
                Map.of("name", "brokers-0", "start", 1, "end", 3),
                Map.of("name", "brokers-1", "start", 7, "end", 8),
                Map.of("name", "brokers-2", "start", 100, "end", 100)
            );
    }

    @Test
    void theProxyParsesTheRenderedConfiguration() {
        var yaml = ProxyLauncher.toYaml(
            ProxyLauncher.configuration("broker-a:9092,broker-b:9092", 19092, new TreeSet<>(List.of(1, 2, 5)))
        );

        var configuration = new ConfigParser().parseConfiguration(yaml);

        assertThat(configuration.clusterDefinitions()).singleElement()
            .satisfies(cluster -> assertThat(cluster.bootstrapServers()).isEqualTo("broker-a:9092,broker-b:9092"));
        assertThat(configuration.filterDefinitions()).singleElement()
            .satisfies(filter -> assertThat(filter.type()).isEqualTo(NetworkFaultFilterFactory.class.getName()));
        assertThat(configuration.defaultFilters()).containsExactly(configuration.filterDefinitions().get(0).name());

        assertThat(configuration.virtualClusters()).singleElement().satisfies(virtualCluster -> {
            assertThat(virtualCluster.drainTimeout()).isEqualTo(Duration.ofMillis(100));
            PortIdentifiesNodeIdentificationStrategy strategy =
                virtualCluster.gateways().get(0).portIdentifiesNode();
            assertThat(strategy.getBootstrapAddress().toString()).isEqualTo("localhost:19092");
            assertThat(strategy.getNodeIdRanges())
                .containsExactly(new NamedRange("brokers-0", 1, 2), new NamedRange("brokers-1", 5, 5));
        });
        assertThat(configuration.network().proxy().shutdownQuietPeriod()).contains(Duration.ZERO);
        assertThat(configuration.network().proxy().shutdownTimeout()).contains(Duration.ofSeconds(2));
    }

    @Test
    void findsTheRequestedBlockWhenItIsFree() throws Exception {
        var free = freePort();

        assertThat(ProxyLauncher.findFreeBlock(free, 1)).isEqualTo(free);
    }

    @Test
    void skipsABlockThatContainsABusyPort() throws Exception {
        try (var busy = new ServerSocket(0)) {
            var busyPort = busy.getLocalPort();

            assertThat(ProxyLauncher.findFreeBlock(busyPort, 2)).isGreaterThan(busyPort);
            // The busy port is the last of the requested block, so the whole block moves past it.
            assertThat(ProxyLauncher.findFreeBlock(busyPort - 1, 2)).isGreaterThan(busyPort);
        }
    }

    @Test
    void failsWhenNoBlockFitsBelowTheLastPort() {
        assertThatThrownBy(() -> ProxyLauncher.findFreeBlock(65535, 2)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAnInvalidPort() {
        assertThatThrownBy(() -> new ProxyLauncher("localhost:9092", 0, Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProxyLauncher("localhost:9092", 65536, Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
