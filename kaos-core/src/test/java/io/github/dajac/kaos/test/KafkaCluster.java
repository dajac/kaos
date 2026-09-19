package io.github.dajac.kaos.test;

import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.utils.AppInfoParser;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A single-broker Kafka cluster running in a container. Integration tests
 * receive it as a parameter from {@link KafkaClusterExtension}.
 *
 * <p>The cluster is shared by all the test classes of a JVM, so tests
 * isolate themselves with {@link #uniqueName(String)} rather than assume an
 * empty cluster.
 */
public final class KafkaCluster implements AutoCloseable {

    /** The broker runs the same Kafka version as the kafka-clients on the classpath. */
    static final DockerImageName IMAGE =
        DockerImageName.parse("apache/kafka").withTag(AppInfoParser.getVersion());

    private final KafkaContainer container;

    private KafkaCluster(KafkaContainer container) {
        this.container = container;
    }

    /** Starts a broker and returns once it accepts connections. */
    static KafkaCluster start() {
        KafkaContainer container = new KafkaContainer(IMAGE);
        container.start();
        return new KafkaCluster(container);
    }

    /** The {@code bootstrap.servers} value that reaches the broker from the test JVM. */
    public String bootstrapServers() {
        return container.getBootstrapServers();
    }

    /** Creates an admin client for the cluster. The caller closes it. */
    public Admin createAdmin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()));
    }

    /** Returns a topic or group name that no other test of the JVM uses. */
    public String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** Stops the broker and removes its container. */
    @Override
    public void close() {
        container.stop();
    }
}
