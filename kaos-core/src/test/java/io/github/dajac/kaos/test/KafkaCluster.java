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
 * receive it as a parameter from {@link KafkaClusterExtension}. See
 * {@link #image()} for the image it runs.
 *
 * <p>The cluster is shared by all the test classes of a JVM, so tests
 * isolate themselves with {@link #uniqueName(String)} rather than assume an
 * empty cluster.
 */
public final class KafkaCluster implements AutoCloseable {

    /**
     * System property that replaces the broker image, for example
     * {@code -Dkaos.test.kafka.image=apache/kafka:4.2.0}. It is meant for a
     * kafka-clients version that has no published {@code apache/kafka} image.
     */
    public static final String IMAGE_PROPERTY = "kaos.test.kafka.image";

    private final KafkaContainer container;

    private KafkaCluster(KafkaContainer container) {
        this.container = container;
    }

    /** Starts a broker and returns once it accepts connections. */
    static KafkaCluster start() {
        KafkaContainer container = new KafkaContainer(image());
        container.start();
        return new KafkaCluster(container);
    }

    /**
     * The broker image. By default it is {@code apache/kafka} with the version
     * of the kafka-clients on the classpath as its tag, so the broker and the
     * clients match. {@link #IMAGE_PROPERTY} replaces the whole image name.
     */
    static DockerImageName image() {
        String override = System.getProperty(IMAGE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return DockerImageName.parse(override.trim()).asCompatibleSubstituteFor("apache/kafka");
        }
        return DockerImageName.parse("apache/kafka").withTag(AppInfoParser.getVersion());
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
