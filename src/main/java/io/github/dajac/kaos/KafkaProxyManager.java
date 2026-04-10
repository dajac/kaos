package io.github.dajac.kaos;

import io.kroxylicious.proxy.KafkaProxy;
import io.kroxylicious.proxy.config.ConfigParser;
import io.kroxylicious.proxy.config.Configuration;
import io.kroxylicious.proxy.internal.config.Features;

public class KafkaProxyManager {

    private final String bootstrapServers;
    private final int proxyBasePort;
    private KafkaProxy proxy;
    private String proxyBootstrapServers;

    public KafkaProxyManager(String bootstrapServers, int proxyBasePort) {
        this.bootstrapServers = bootstrapServers;
        this.proxyBasePort = proxyBasePort;
    }

    public String proxyBootstrapServers() {
        return proxyBootstrapServers;
    }

    public void start() throws Exception {
        PartitionFilter.clear();

        String yaml = "filterDefinitions:\n" +
            "  - name: partition-filter\n" +
            "    type: " + PartitionFilter.class.getName() + "\n" +
            "defaultFilters:\n" +
            "  - partition-filter\n" +
            "virtualClusters:\n" +
            "  - name: simulator\n" +
            "    targetCluster:\n" +
            "      bootstrapServers: " + bootstrapServers + "\n" +
            "    gateways:\n" +
            "      - name: default\n" +
            "        portIdentifiesNode:\n" +
            "          bootstrapAddress: localhost:" + proxyBasePort + "\n";

        ConfigParser parser = new ConfigParser();
        Configuration config = parser.parseConfiguration(yaml);

        proxy = new KafkaProxy(parser, config, Features.defaultFeatures());
        proxy.startup();

        proxyBootstrapServers = "localhost:" + proxyBasePort;
    }

    public void stop() {
        if (proxy != null) {
            try {
                proxy.close();
            } catch (Exception e) {
                // Ignore.
            }
            proxy = null;
        }
        PartitionFilter.clear();
    }
}
