package io.github.dajac.kaos.test;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
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
 * empty cluster, and launch their proxies from {@link #proxyPort()} rather
 * than from the default proxy port.
 */
public final class KafkaCluster implements AutoCloseable {

    /**
     * System property that replaces the broker image, for example
     * {@code -Dkaos.test.kafka.image=apache/kafka:4.2.0}. It is meant for a
     * kafka-clients version that has no published {@code apache/kafka} image.
     */
    public static final String IMAGE_PROPERTY = "kaos.test.kafka.image";

    /**
     * The ports {@link #proxyPort()} is drawn from: above the default proxy
     * port, below the ephemeral ports of Linux and macOS, which the Kafka
     * clients and the container runtime allocate from, and wide enough for
     * two JVMs to draw far apart from each other.
     */
    static final int PROXY_PORT_MIN = 20000;
    static final int PROXY_PORT_MAX = 30000;

    private static final Duration TOPIC_TIMEOUT = Duration.ofSeconds(30);

    private final KafkaContainer container;
    private final int proxyPort;

    private KafkaCluster(KafkaContainer container, int proxyPort) {
        this.container = container;
        this.proxyPort = proxyPort;
    }

    /** Starts a broker and returns once it accepts connections. */
    static KafkaCluster start() {
        var container = new KafkaContainer(image())
            // The defaults of the broker refuse a consumer group session timeout under 45 s and a
            // heartbeat interval under 5 s, which would make every eviction test that long.
            .withEnv("KAFKA_GROUP_CONSUMER_MIN_SESSION_TIMEOUT_MS", "1000")
            .withEnv("KAFKA_GROUP_CONSUMER_MIN_HEARTBEAT_INTERVAL_MS", "500");
        container.start();
        return new KafkaCluster(container, ThreadLocalRandom.current().nextInt(PROXY_PORT_MIN, PROXY_PORT_MAX));
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

    /**
     * The port the tests of this JVM request for their proxies. It is drawn
     * at random when the cluster starts, so the proxies of two builds running
     * on one machine, and the proxy of a simulator running next to the build,
     * bind far apart and never take each other's ports. The proxies of one
     * JVM all request it and move up from there, as the launcher does when a
     * port is busy.
     */
    public int proxyPort() {
        return proxyPort;
    }

    /** Creates an admin client for the cluster. The caller closes it. */
    public Admin createAdmin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()));
    }

    /** Returns a topic or group name that no other test of the JVM uses. */
    public String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /**
     * Waits until the broker reports a topic. The creation of a topic
     * completes when the controller has accepted it; the broker serves the
     * topic in its metadata only once the change has reached it, so a
     * describe right after the creation can still fail.
     *
     * @param admin an admin client of the cluster, which stays owned by the
     *     caller
     */
    public void awaitTopic(Admin admin, String topic) {
        await("topic " + topic + " to be reported").atMost(TOPIC_TIMEOUT).until(() -> topicExists(admin, topic));
    }

    private static boolean topicExists(Admin admin, String topic) throws Exception {
        try {
            admin.describeTopics(List.of(topic)).allTopicNames().get(TOPIC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return false;
            }
            throw e;
        }
    }

    /** Stops the broker and removes its container. */
    @Override
    public void close() {
        container.stop();
    }
}
