package io.github.dajac.kaos.internal.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

import io.github.dajac.kaos.internal.proxy.NetworkFaults.Mode;
import io.github.dajac.kaos.test.IntegrationTest;
import io.github.dajac.kaos.test.KafkaCluster;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives real consumers through the proxy and injects network faults by client id. Producers and
 * the admin client connect to the broker directly, as they do in the simulator.
 */
@IntegrationTest
class NetworkFaultInjectionTest {

    private static final Duration LAUNCH_TIMEOUT = Duration.ofSeconds(30);

    /** Enough for a join, or for a session timeout followed by the eviction. */
    private static final Duration GROUP_CHANGE = PollingConsumer.SESSION_TIMEOUT.plusSeconds(30);

    private static final Duration SHORT_REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final List<AutoCloseable> resources = new ArrayList<>();

    private KafkaCluster cluster;
    private Admin admin;
    private String topic;
    private String group;

    @BeforeEach
    void setUp(KafkaCluster cluster) throws Exception {
        this.cluster = cluster;
        this.admin = cluster.createAdmin();
        this.topic = cluster.uniqueName("proxy");
        this.group = cluster.uniqueName("group");
        admin.createTopics(List.of(new NewTopic(topic, 4, (short) 1))).all().get(30, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = resources.size() - 1; i >= 0; i--) {
            resources.get(i).close();
        }
        admin.close();
    }

    @Test
    void blackholeEvictsTheClientAndClearingLetsItRejoin() throws Exception {
        NetworkProxy proxy = launch(ProxyLauncher.DEFAULT_PORT);
        PollingConsumer consumer = consumer(proxy, group, "isolated", SHORT_REQUEST_TIMEOUT);
        awaitMembers(group, "isolated");

        proxy.faults().set("isolated", Mode.BLACKHOLE);

        // The heartbeats are dropped, so the coordinator expires the session.
        awaitMembers(group);

        proxy.faults().clear("isolated");

        awaitMembers(group, "isolated");
        produce("after-rejoin");
        await().atMost(GROUP_CHANGE).untilAsserted(() -> assertThat(consumer.values()).contains("after-rejoin"));
    }

    @Test
    void isolatingOneClientLeavesTheOtherClientOfTheProxyUnaffected() throws Exception {
        NetworkProxy proxy = launch(ProxyLauncher.DEFAULT_PORT);
        PollingConsumer healthy = consumer(proxy, group, "healthy", SHORT_REQUEST_TIMEOUT);
        consumer(proxy, group, "isolated", SHORT_REQUEST_TIMEOUT);
        awaitMembers(group, "healthy", "isolated");

        proxy.faults().set("isolated", Mode.BLACKHOLE);

        // Only the isolated client is evicted. The healthy one stays in the group, takes over all
        // the partitions and keeps consuming through the same proxy.
        awaitMembers(group, "healthy");
        List<String> values = produceToEveryPartition("while-isolated");
        await().atMost(GROUP_CHANGE).untilAsserted(() -> assertThat(healthy.values()).containsAll(values));

        proxy.faults().clear("isolated");

        awaitMembers(group, "healthy", "isolated");
    }

    @Test
    void resetDisconnectsTheClientAndClearingRecoversQuickly() throws Exception {
        NetworkProxy proxy = launch(ProxyLauncher.DEFAULT_PORT);
        // With the default request timeout, a client whose requests were dropped would be stuck
        // for 30 s. A client whose connections are closed notices at once.
        PollingConsumer consumer = consumer(proxy, group, "reset", DEFAULT_REQUEST_TIMEOUT);
        awaitMembers(group, "reset");
        produce("before-reset");
        await().atMost(GROUP_CHANGE).untilAsserted(() -> assertThat(consumer.values()).contains("before-reset"));
        double closedBefore = consumer.closedConnections();

        proxy.faults().set("reset", Mode.RESET);

        // Every reconnection attempt is closed again on its first request.
        await().atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(consumer.closedConnections()).isGreaterThanOrEqualTo(closedBefore + 5));

        proxy.faults().clear("reset");
        produce("after-reset");

        await().atMost(DEFAULT_REQUEST_TIMEOUT.dividedBy(2))
            .untilAsserted(() -> assertThat(consumer.values()).contains("after-reset"));
    }

    @Test
    void twoProxiesInOneJvmHaveIndependentFaults() throws Exception {
        NetworkProxy first = launch(ProxyLauncher.DEFAULT_PORT);
        NetworkProxy second = launch(ProxyLauncher.DEFAULT_PORT);
        assertThat(second.bootstrapServers()).isNotEqualTo(first.bootstrapServers());
        assertThat(second.faults()).isNotSameAs(first.faults());

        // The same client id goes through both proxies, in two groups.
        String otherGroup = cluster.uniqueName("group");
        consumer(first, group, "same-id", SHORT_REQUEST_TIMEOUT);
        PollingConsumer viaSecond = consumer(second, otherGroup, "same-id", SHORT_REQUEST_TIMEOUT);
        awaitMembers(group, "same-id");
        awaitMembers(otherGroup, "same-id");

        first.faults().set("same-id", Mode.BLACKHOLE);

        awaitMembers(group);
        assertThat(members(otherGroup)).containsExactly("same-id");

        // Closing one proxy leaves the other one serving.
        first.close();
        List<String> values = produceToEveryPartition("first-closed");
        await().atMost(GROUP_CHANGE).untilAsserted(() -> assertThat(viaSecond.values()).containsAll(values));
        assertThat(members(otherGroup)).containsExactly("same-id");
    }

    @Test
    void fallsBackToTheNextFreeBlockWhenTheRequestedPortIsBusy() throws Exception {
        try (ServerSocket busy = new ServerSocket()) {
            busy.bind(new InetSocketAddress(0));
            int busyPort = busy.getLocalPort();

            NetworkProxy proxy = launch(busyPort);

            assertThat(port(proxy)).isGreaterThan(busyPort);
            PollingConsumer consumer = consumer(proxy, group, "fallback", SHORT_REQUEST_TIMEOUT);
            produce("through-fallback");
            await().atMost(GROUP_CHANGE)
                .untilAsserted(() -> assertThat(consumer.values()).contains("through-fallback"));
        }
    }

    @Test
    void fallsBackWhenABrokerPortOfTheBlockIsBusy() throws Exception {
        try (ServerSocket busy = new ServerSocket()) {
            busy.bind(new InetSocketAddress(0));
            int busyPort = busy.getLocalPort();

            // The block is the bootstrap port plus one port for the single broker.
            NetworkProxy proxy = launch(busyPort - 1);

            assertThat(port(proxy)).isGreaterThan(busyPort);
        }
    }

    @Test
    void movesToTheNextBlockWhenAPortIsTakenBetweenTheProbeAndTheBind() throws Exception {
        Set<Thread> threadsBefore = Thread.getAllStackTraces().keySet();
        List<Integer> probed = new ArrayList<>();
        // Takes the broker port of the first block found free, right after the probe.
        IntConsumer takeBrokerPortOnce = port -> {
            probed.add(port);
            if (probed.size() == 1) {
                resources.add(listenOn(port + 1));
            }
        };

        NetworkProxy proxy = new ProxyLauncher(cluster.bootstrapServers(), ProxyLauncher.DEFAULT_PORT, LAUNCH_TIMEOUT, takeBrokerPortOnce)
            .launch(admin, new NetworkFaults());
        resources.add(proxy);

        assertThat(probed).hasSize(2);
        assertThat(port(proxy)).isEqualTo(probed.get(1)).isGreaterThan(probed.get(0) + 1);
        // The failed attempt released the bootstrap port it had bound.
        assertThat(ProxyLauncher.isFree(probed.get(0))).isTrue();
        PollingConsumer consumer = consumer(proxy, group, "after-race", SHORT_REQUEST_TIMEOUT);
        produce("after-race");
        await().atMost(GROUP_CHANGE).untilAsserted(() -> assertThat(consumer.values()).contains("after-race"));

        consumer.close();
        proxy.close();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(proxyThreads(threadsBefore)).isEmpty());
    }

    @Test
    void aLaunchThatKeepsLosingItsPortsFailsAndLeaksNothing() throws Exception {
        Set<Thread> threadsBefore = Thread.getAllStackTraces().keySet();
        List<Integer> probed = new ArrayList<>();
        List<ServerSocket> taken = new ArrayList<>();
        IntConsumer alwaysTakeBootstrapPort = port -> {
            probed.add(port);
            taken.add(listenOn(port));
        };
        resources.add(() -> {
            for (ServerSocket socket : taken) {
                socket.close();
            }
        });
        ProxyLauncher launcher =
            new ProxyLauncher(cluster.bootstrapServers(), ProxyLauncher.DEFAULT_PORT, LAUNCH_TIMEOUT, alwaysTakeBootstrapPort);

        assertThatThrownBy(() -> launcher.launch(admin, new NetworkFaults()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("taken during startup");

        assertThat(probed).hasSize(10);
        // Once the test gives its ports back, nothing listens on any block that was tried: every
        // failed attempt released the broker port it had bound.
        for (ServerSocket socket : taken) {
            socket.close();
        }
        for (int port = probed.get(0); port <= probed.get(probed.size() - 1) + 1; port++) {
            assertThat(ProxyLauncher.isFree(port)).as("port %d", port).isTrue();
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(proxyThreads(threadsBefore)).isEmpty());
    }

    @Test
    void aStartupFailureThatIsNotAboutPortsFailsTheLaunchAndLeaksNothing() throws Exception {
        Set<Thread> threadsBefore = Thread.getAllStackTraces().keySet();
        List<Integer> probed = new ArrayList<>();
        ProxyLauncher launcher = new ProxyLauncher("not a bootstrap address", ProxyLauncher.DEFAULT_PORT, LAUNCH_TIMEOUT, probed::add);

        assertThatThrownBy(() -> launcher.launch(admin, new NetworkFaults()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("failed to start");

        assertThat(probed).hasSize(1);
        probed.forEach(port -> {
            assertThat(ProxyLauncher.isFree(port)).isTrue();
            assertThat(ProxyLauncher.isFree(port + 1)).isTrue();
        });
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(proxyThreads(threadsBefore)).isEmpty());
    }

    private static ServerSocket listenOn(int port) {
        try {
            ServerSocket socket = new ServerSocket();
            socket.bind(new InetSocketAddress(port));
            return socket;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void closeIsIdempotentBoundedAndReleasesPortsAndThreads() throws Exception {
        Set<Thread> threadsBefore = Thread.getAllStackTraces().keySet();
        NetworkProxy proxy = launch(ProxyLauncher.DEFAULT_PORT);
        int port = port(proxy);
        // The eviction proves that the proxy dropped requests of the client. With the default
        // request timeout the client still waits for them, so its connections are open, with
        // requests that never complete, when the proxy closes.
        consumer(proxy, group, "in-flight", DEFAULT_REQUEST_TIMEOUT);
        awaitMembers(group, "in-flight");
        proxy.faults().set("in-flight", Mode.BLACKHOLE);
        awaitMembers(group);

        long start = System.nanoTime();
        proxy.close();
        proxy.close();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
        // The block is the bootstrap port plus one port for the single broker.
        assertThat(ProxyLauncher.isFree(port)).isTrue();
        assertThat(ProxyLauncher.isFree(port + 1)).isTrue();
        // Netty's global executor thread outlives the event loops by about a second.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(proxyThreads(threadsBefore)).isEmpty());
    }

    /** The threads started since {@code before}, except the test's own Kafka clients and Awaitility. */
    private static List<String> proxyThreads(Set<Thread> before) {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isAlive)
            .filter(thread -> !before.contains(thread))
            .map(Thread::getName)
            .filter(name -> !name.startsWith("test-") && !name.startsWith("kafka-") && !name.startsWith("awaitility"))
            .sorted()
            .collect(Collectors.toList());
    }

    private NetworkProxy launch(int requestedPort) {
        NetworkProxy proxy = new ProxyLauncher(cluster.bootstrapServers(), requestedPort, LAUNCH_TIMEOUT)
            .launch(admin, new NetworkFaults());
        resources.add(proxy);
        return proxy;
    }

    private PollingConsumer consumer(NetworkProxy proxy, String groupId, String clientId, Duration requestTimeout) {
        PollingConsumer consumer = new PollingConsumer(proxy.bootstrapServers(), groupId, clientId, topic, requestTimeout);
        resources.add(consumer);
        return consumer;
    }

    private static int port(NetworkProxy proxy) {
        String bootstrapServers = proxy.bootstrapServers();
        return Integer.parseInt(bootstrapServers.substring(bootstrapServers.lastIndexOf(':') + 1));
    }

    private void awaitMembers(String groupId, String... clientIds) {
        await().atMost(GROUP_CHANGE)
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> assertThat(members(groupId)).containsExactlyInAnyOrder(clientIds));
    }

    /** The client ids of the members of the group, as the coordinator sees them. */
    private List<String> members(String groupId) throws Exception {
        return admin.describeConsumerGroups(List.of(groupId))
            .describedGroups()
            .get(groupId)
            .get(30, TimeUnit.SECONDS)
            .members()
            .stream()
            .map(member -> member.clientId())
            .collect(Collectors.toList());
    }

    private void produce(String value) throws Exception {
        try (KafkaProducer<String, String> producer = producer()) {
            producer.send(new ProducerRecord<>(topic, value)).get(30, TimeUnit.SECONDS);
        }
    }

    /** Produces one record to each partition of the topic, and returns the values. */
    private List<String> produceToEveryPartition(String prefix) throws Exception {
        List<String> values = new ArrayList<>();
        try (KafkaProducer<String, String> producer = producer()) {
            for (int partition = 0; partition < 4; partition++) {
                String value = prefix + "-" + partition;
                producer.send(new ProducerRecord<>(topic, partition, null, value)).get(30, TimeUnit.SECONDS);
                values.add(value);
            }
        }
        return values;
    }

    private KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }
}
