package io.github.dajac.kaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** The harness before {@code start()}, which needs no cluster. */
class SimulatorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void refusesInvalidSettings() {
        assertThatThrownBy(() -> new Simulator(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Simulator(" ")).isInstanceOf(IllegalArgumentException.class);
        try (var simulator = new Simulator("localhost:9092")) {
            assertThatThrownBy(() -> simulator.withProxyPort(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> simulator.withProxyPort(65536)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> simulator.withClientProperty(null, "v")).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> simulator.withListener(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void theInitialSnapshotIsIdleAndEmpty() {
        try (var simulator = new Simulator("localhost:9092")) {
            assertThat(simulator.snapshot()).isEqualTo(new SimulatorSnapshot(0, Phase.IDLE, false, "", List.of(), List.of(), List.of()));
            assertThat(simulator.describe()).isEqualTo(new SimulatorSpec(List.of(), List.of(), List.of()));
        }
    }

    @Test
    void describesTheDeclaredComponents() {
        try (var simulator = new Simulator("localhost:9092")) {
            var orders = simulator.addTopic("orders", 12);
            var payments = simulator.addTopic(TopicSpec.of("payments"));
            var first = simulator.addProducer(p -> p.topics(orders, payments));
            var named = simulator.addProducer(new ProducerSpec("producer-1", List.of("orders"), 10));
            var third = simulator.addProducer(p -> p.topics("payments").ratePerPartition(0.5));

            assertThat(orders.name()).isEqualTo("orders");
            assertThat(orders.partitions()).isEqualTo(12);
            assertThat(payments.partitions()).isEqualTo(36);
            assertThat(first.id()).isEqualTo("producer-0");
            assertThat(first.ratePerPartition()).isEqualTo(2.0);
            assertThat(first.topics()).containsExactly("orders", "payments");
            // The id producer-1 is taken, so the next assigned id skips it.
            assertThat(third.id()).isEqualTo("producer-2");
            assertThat(GroupSpec.of("group", g -> g.subscribe(orders, payments)).subscribedTopics())
                .containsExactly("orders", "payments");
            assertThat(simulator.topics()).containsExactly(orders, payments);
            assertThat(simulator.producers()).containsExactly(first, named, third);
            assertThat(simulator.describe()).isEqualTo(new SimulatorSpec(
                List.of(new TopicSpec("orders", 12), new TopicSpec("payments", 36)),
                List.of(
                    new ProducerSpec("producer-0", List.of("orders", "payments"), 2.0),
                    new ProducerSpec("producer-1", List.of("orders"), 10),
                    new ProducerSpec("producer-2", List.of("payments"), 0.5)
                ),
                List.of()
            ));
        }
    }

    @Test
    void theSnapshotShowsTheDeclaredComponentsAsStopped() {
        var received = new CopyOnWriteArrayList<SimulatorSnapshot>();
        try (var simulator = new Simulator("localhost:9092").withListener(received::add)) {
            simulator.addTopic("orders", 3);
            var producer = simulator.addProducer(p -> p.topics("orders"));

            var snapshot = simulator.awaitSnapshot(s -> !s.producers().isEmpty(), TIMEOUT);

            assertThat(snapshot.phase()).isEqualTo(Phase.IDLE);
            assertThat(snapshot.topics()).containsExactly(new TopicSpec("orders", 3));
            assertThat(snapshot.producers())
                .containsExactly(new ProducerSnapshot("producer-0", List.of("orders"), ProducerState.STOPPED, 2.0));
            assertThat(producer.state()).isEqualTo(ProducerState.STOPPED);
            assertThat(received).contains(snapshot);
        }
    }

    @Test
    void refusesInvalidComponentsAndKeepsTheValidOnes() {
        try (var simulator = new Simulator("localhost:9092")) {
            simulator.addTopic("orders", 3);
            simulator.addProducer(p -> p.id("load").topics("orders"));

            assertThatThrownBy(() -> simulator.addTopic("orders", 3))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.violations())
                    .containsExactly(new Violation("topics[1].name", "duplicate topic 'orders'")));
            assertThatThrownBy(() -> simulator.addTopic("not valid", 0))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.violations())
                    .extracting(Violation::field)
                    .containsExactly("topics[1].name", "topics[1].partitions"));
            assertThatThrownBy(() -> simulator.addProducer(p -> p.topics("unknown")))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.violations())
                    .containsExactly(new Violation("producers[1].topics[0]", "unknown topic 'unknown'")));
            assertThatThrownBy(() -> simulator.addProducer(p -> p.id("load").topics("orders")))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.violations())
                    .containsExactly(new Violation("producers[1].id", "duplicate producer 'load'")));
            assertThatThrownBy(() -> simulator.addProducer(p -> p.topics("orders").ratePerPartition(0)))
                .isInstanceOf(InvalidConfigException.class);

            assertThat(simulator.describe().topics()).hasSize(1);
            assertThat(simulator.describe().producers()).hasSize(1);
            // A refused producer does not use up an id.
            assertThat(simulator.addProducer(p -> p.topics("orders")).id()).isEqualTo("producer-0");
        }
    }

    @Test
    void closeRemovesTheComponentsAndEndsThePublisherThread() {
        var simulator = new Simulator("localhost:9092");
        simulator.addTopic("orders", 3);
        simulator.addProducer(p -> p.topics("orders"));
        simulator.awaitSnapshot(s -> !s.producers().isEmpty(), TIMEOUT);
        assertThat(simulatorThreads()).containsExactly("kaos-publisher");

        simulator.close();

        assertThat(simulatorThreads()).isEmpty();
        assertThat(simulator.describe()).isEqualTo(new SimulatorSpec(List.of(), List.of(), List.of()));
        assertThat(simulator.snapshot().topics()).isEmpty();
        assertThat(simulator.snapshot().producers()).isEmpty();
        assertThatCode(simulator::close).doesNotThrowAnyException();
        assertThatCode(simulator::stop).doesNotThrowAnyException();

        // The simulator can be used again, and assigns the ids from the start.
        simulator.addTopic("again", 1);
        assertThat(simulator.addProducer(p -> p.topics("again")).id()).isEqualTo("producer-0");
        simulator.close();
    }

    @Test
    void theBlockingMethodsFailFastInAListener() {
        var failures = new ConcurrentHashMap<String, Throwable>();
        var readsFailure = new AtomicReference<Throwable>();
        var called = new AtomicBoolean();
        var done = new AtomicBoolean();
        var holder = new AtomicReference<Simulator>();
        SnapshotListener listener = snapshot -> {
            var simulator = holder.get();
            if (!called.compareAndSet(false, true)) {
                return;
            }
            failures.put("close", catchThrowable(simulator::close));
            failures.put("stop", catchThrowable(simulator::stop));
            failures.put("start", catchThrowable(simulator::start));
            failures.put("addTopic", catchThrowable(() -> simulator.addTopic("late", 1)));
            failures.put("addProducer", catchThrowable(() -> simulator.addProducer(p -> p.topics("orders"))));
            failures.put("withClientProperty", catchThrowable(() -> simulator.withClientProperty("key", "value")));
            failures.put("withProxyPort", catchThrowable(() -> simulator.withProxyPort(19092)));
            failures.put("withListener", catchThrowable(() -> simulator.withListener(s -> {})));
            failures.put("awaitSnapshot", catchThrowable(() -> simulator.awaitSnapshot(s -> true, TIMEOUT)));
            // The methods that do not block are allowed.
            readsFailure.set(catchThrowable(() -> {
                simulator.snapshot();
                simulator.describe();
                simulator.addListener(s -> {}).close();
            }));
            done.set(true);
        };

        try (var simulator = new Simulator("localhost:9092").withListener(listener)) {
            holder.set(simulator);
            simulator.addTopic("orders", 3);
            simulator.awaitSnapshot(s -> !s.topics().isEmpty(), TIMEOUT);
            await().atMost(TIMEOUT).untilTrue(done);

            assertThat(readsFailure.get()).isNull();
            assertThat(failures).hasSize(9).allSatisfy((method, failure) -> assertThat(failure).as(method)
                .isInstanceOf(SimulationStateException.class)
                .hasMessageContaining("must not be called from a snapshot listener"));
            // The simulator is unharmed: nothing was added, and it still declares and closes.
            assertThat(simulator.describe().topics()).containsExactly(new TopicSpec("orders", 3));
            simulator.addTopic("payments", 1);
        }
    }

    @Test
    void aStopWithNothingRunningKeepsTheLastError() {
        var received = new CopyOnWriteArrayList<SimulatorSnapshot>();
        // The admin client refuses the property, so the start fails before it reaches a cluster.
        try (var simulator = new Simulator("localhost:9092").withClientProperty("security.protocol", "bogus").withListener(received::add)) {
            simulator.addTopic("orders", 3);

            var failure = catchThrowable(simulator::start);

            assertThat(failure).isInstanceOf(SimulationException.class).hasMessageContaining("The simulator failed to start");
            assertThat(simulator.snapshot().lastError()).isEqualTo(failure.getMessage());
            assertThat(simulator.snapshot().topics()).hasSize(1);

            simulator.stop();

            var last = received.get(received.size() - 1);
            assertThat(last).isEqualTo(simulator.snapshot());
            assertThat(last.phase()).isEqualTo(Phase.IDLE);
            assertThat(last.topics()).isEmpty();
            assertThat(last.lastError()).isEqualTo(failure.getMessage());
            assertThat(simulatorThreads()).isEmpty();

            simulator.stop();
            assertThat(simulator.snapshot().lastError()).isEqualTo(failure.getMessage());
        }
    }

    private static List<String> simulatorThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isAlive)
            .map(Thread::getName)
            .filter(name -> name.startsWith("kaos-"))
            .toList();
    }

    @Test
    void severalSimulatorsDeclareTheSameNames() {
        try (var first = new Simulator("localhost:9092"); var second = new Simulator("localhost:9092")) {
            first.addTopic("orders", 3);
            second.addTopic("orders", 5);

            assertThat(first.describe().topics()).containsExactly(new TopicSpec("orders", 3));
            assertThat(second.describe().topics()).containsExactly(new TopicSpec("orders", 5));
        }
    }
}
