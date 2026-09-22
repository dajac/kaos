/*
 * Copyright 2026 The Kaos Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dajac.kaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

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
            assertThat(simulator.snapshot())
                .isEqualTo(new SimulatorSnapshot(0, Phase.IDLE, false, "", List.of(), List.of(), List.of()));
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
    void describesTheDeclaredGroups() {
        try (var simulator = new Simulator("localhost:9092")) {
            var orders = simulator.addTopic("orders", 12);
            var defaults = new ConsumerSettings(Protocol.CLASSIC, "", false, "", 0, 1000, 300_000);
            var rackA = ConsumerOptions.overrides(c -> c.rackId("a"));
            var group = simulator.addGroup(new GroupSpec(
                "next",
                List.of("orders"),
                10_000,
                5000,
                1000,
                defaults,
                List.of(
                    new ConsumerSpec("consumer-1", rackA, null), ConsumerSpec.inheriting(), ConsumerSpec.inheriting()
                )
            ));
            var fromSpec = simulator.addGroup(new GroupSpec("other", List.of("orders"), 45000, 5000, 1000, null, null));

            assertThat(group.id()).isEqualTo("next");
            assertThat(group.subscribedTopics()).containsExactly("orders");
            // The supplied id is kept, and the assigned ids skip it.
            assertThat(group.consumers()).extracting(ConsumerHandle::id)
                .containsExactly("consumer-1", "consumer-0", "consumer-2");
            assertThat(group.consumer(2)).isSameAs(group.consumer("consumer-2"));
            assertThat(group.consumer(0).clientId()).isEqualTo("next-consumer-0");
            assertThat(group.consumer(0).protocol()).isEqualTo(Protocol.CLASSIC);
            assertThat(group.consumer(0).subscribed()).isTrue();
            assertThat(group.autopilot().enabled()).isFalse();
            assertThat(group.warnings()).isEmpty();
            assertThatThrownBy(() -> group.consumer(3)).isInstanceOf(IllegalArgumentException.class);
            assertThat(fromSpec.consumers()).isEmpty();
            assertThat(simulator.groups()).containsExactly(group, fromSpec);
            assertThat(simulator.describe().groups()).containsExactly(group.describe(), fromSpec.describe());
            assertThat(group.describe().sessionTimeoutMs()).isEqualTo(10_000);
            assertThat(group.describe().consumers()).extracting(ConsumerSpec::id)
                .containsExactly("consumer-1", "consumer-0", "consumer-2");
            assertThat(group.describe().consumers().get(0).effective().rackId()).isEqualTo("a");

            // A declared group shows its consumers as joining, with nothing from the coordinator.
            var snapshot = group.awaitSnapshot(s -> s.consumers().size() == 3, TIMEOUT);
            assertThat(snapshot.type()).isEqualTo(GroupType.UNKNOWN);
            assertThat(snapshot.consumers()).extracting(ConsumerSnapshot::status).containsOnly(ConsumerStatus.JOINING);
            assertThat(snapshot.lag()).containsExactly(Map.entry("orders", java.util.Collections.nCopies(12, -1L)));
            var both = simulator.awaitSnapshot(s -> s.groups().size() == 2, TIMEOUT);
            assertThat(both.groups()).containsExactly(snapshot, fromSpec.snapshot());

            // The flags flip before the start, and the snapshot shows them.
            group.consumer(0).leave();
            group.consumer(0).inject(Fault.freeze());
            group.consumer(0).switchProtocol(Protocol.CONSUMER);
            var flipped = group.awaitSnapshot(
                s -> !s.consumers().get(1).subscribed()
                    && !s.consumers().get(1).faults().isEmpty()
                    && s.consumers().get(1).protocol() == Protocol.CONSUMER,
                TIMEOUT
            );
            assertThat(flipped.consumers().get(1).status()).isEqualTo(ConsumerStatus.STOPPED);
            assertThat(flipped.consumers().get(1).faults()).containsExactly(Fault.freeze());
            assertThat(flipped.consumers().get(1).protocol()).isEqualTo(Protocol.CONSUMER);
            assertThatThrownBy(() -> group.autopilot().start(Duration.ofSeconds(1), Duration.ofSeconds(2)))
                .isInstanceOf(SimulationStateException.class);
        }
    }

    @Test
    void refusesInvalidGroups() {
        try (var simulator = new Simulator("localhost:9092")) {
            simulator.addTopic("orders", 3);
            simulator.addGroup(new GroupSpec("a-b", List.of("orders"), 45000, 5000, 1000, null, List.of(named("c"))));

            assertThatThrownBy(() -> simulator.addGroup("a-b", g -> g.subscribe("orders")))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.violations())
                    .containsExactly(new Violation("groups[1].id", "duplicate group 'a-b'")));
            assertThatThrownBy(() -> simulator.addGroup("g", g -> g.subscribe("unknown")))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.violations())
                    .containsExactly(new Violation("groups[1].subscribedTopics[0]", "unknown topic 'unknown'")));
            assertThatThrownBy(() -> simulator.addGroup("g", g -> g
                .subscribe("orders")
                .consumerDefaults(c -> c.protocol(Protocol.CLASSIC).assignor("range"))
                .consumer(c -> c.assignor("cooperative-sticky"))
                .consumers(1)
            ))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.violations())
                    .extracting(Violation::field).containsExactly("groups[1].consumers"));
            // The client id a-b-c is taken, whichever way it is split into a group and a consumer.
            assertThatThrownBy(() -> simulator.addGroup(
                new GroupSpec("a", List.of("orders"), 45000, 5000, 1000, null, List.of(named("b-c")))
            ))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.violations())
                    .containsExactly(new Violation(
                        "groups[1].consumers[0].id",
                        "the client id 'a-b-c' is already used by a consumer of group 'a-b'"
                    )));

            assertThat(simulator.groups()).hasSize(1);
        }
    }

    private static ConsumerSpec named(String id) {
        return new ConsumerSpec(id, null, null);
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
    void removesDeclaredComponentsAndRefusesATopicInUse() {
        try (var simulator = new Simulator("localhost:9092")) {
            var orders = simulator.addTopic("orders", 3);
            var payments = simulator.addTopic("payments", 2);
            var producer = simulator.addProducer(p -> p.topics(orders));
            var group = simulator.addGroup("app", g -> g.subscribe(orders, payments).consumers(1));

            assertThatThrownBy(orders::remove).isInstanceOf(SimulationStateException.class)
                .hasMessageContaining("producer producer-0").hasMessageContaining("group app");
            assertThatThrownBy(payments::remove).isInstanceOf(SimulationStateException.class)
                .hasMessageContaining("group app");

            group.remove();
            payments.remove();
            producer.remove();
            orders.remove();

            assertThat(simulator.describe()).isEqualTo(new SimulatorSpec(List.of(), List.of(), List.of()));
            assertThat(simulator.topics()).isEmpty();
            assertThat(simulator.producers()).isEmpty();
            assertThat(simulator.groups()).isEmpty();
            simulator.awaitSnapshot(s -> s.topics().isEmpty() && s.producers().isEmpty() && s.groups().isEmpty(),
                TIMEOUT);
            // A name is free again once its component is removed.
            simulator.addTopic("orders", 1);
        }
    }

    @Test
    void aRemovedHandleRefusesToAct() {
        try (var simulator = new Simulator("localhost:9092")) {
            var orders = simulator.addTopic("orders", 3);
            var producer = simulator.addProducer(p -> p.topics(orders));
            var group = simulator.addGroup("app", g -> g.subscribe(orders).consumers(2));
            var consumer = group.consumer(0);

            consumer.remove();

            assertThat(group.consumers()).extracting(ConsumerHandle::id).containsExactly("consumer-1");
            assertThatThrownBy(() -> group.consumer(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(consumer::remove).isInstanceOf(SimulationStateException.class);
            assertThatThrownBy(consumer::leave).isInstanceOf(SimulationStateException.class);
            assertThatThrownBy(consumer::join).isInstanceOf(SimulationStateException.class);
            assertThatThrownBy(() -> consumer.inject(Fault.freeze())).isInstanceOf(SimulationStateException.class);
            assertThatThrownBy(() -> consumer.clear(Fault.freeze())).isInstanceOf(SimulationStateException.class);
            assertThatThrownBy(consumer::clearFaults).isInstanceOf(SimulationStateException.class);
            assertThatThrownBy(() -> consumer.switchProtocol(Protocol.CLASSIC))
                .isInstanceOf(SimulationStateException.class);
            // The accessors keep answering.
            assertThat(consumer.id()).isEqualTo("consumer-0");

            var remaining = group.consumer(1);
            group.remove();

            assertThatThrownBy(group::remove).isInstanceOf(SimulationStateException.class);
            assertThatThrownBy(() -> group.addConsumer(c -> {})).isInstanceOf(SimulationStateException.class);
            assertThatThrownBy(remaining::leave).isInstanceOf(SimulationStateException.class);
            assertThat(group.id()).isEqualTo("app");

            producer.remove();

            assertThatThrownBy(producer::remove).isInstanceOf(SimulationStateException.class);
            assertThatThrownBy(producer::pause).isInstanceOf(SimulationStateException.class);
            assertThatThrownBy(producer::resume).isInstanceOf(SimulationStateException.class);

            orders.remove();

            assertThatThrownBy(orders::remove).isInstanceOf(SimulationStateException.class);
        }
    }

    @Test
    void theHandlesOfAStoppedSimulatorRefuseToAct() {
        var simulator = new Simulator("localhost:9092");
        var orders = simulator.addTopic("orders", 3);
        var producer = simulator.addProducer(p -> p.topics(orders));
        var group = simulator.addGroup("app", g -> g.subscribe(orders).consumers(1));

        simulator.close();

        assertThatThrownBy(orders::remove).isInstanceOf(SimulationStateException.class);
        assertThatThrownBy(producer::pause).isInstanceOf(SimulationStateException.class);
        assertThatThrownBy(group::remove).isInstanceOf(SimulationStateException.class);
        assertThatThrownBy(() -> group.consumer(0).leave()).isInstanceOf(SimulationStateException.class);
    }

    @Test
    void assignedIdsAreNeverReused() {
        try (var simulator = new Simulator("localhost:9092")) {
            simulator.addTopic("orders", 3);
            var first = simulator.addProducer(p -> p.topics("orders"));
            var named = simulator.addProducer(p -> p.id("producer-1").topics("orders"));
            first.remove();
            named.remove();

            assertThat(simulator.addProducer(p -> p.topics("orders")).id()).isEqualTo("producer-2");

            var group = simulator.addGroup("app", g -> g.subscribe("orders").consumers(2));
            group.consumer(1).remove();
            var added = group.addConsumer(c -> c.rackId("b"));

            assertThat(added.id()).isEqualTo("consumer-2");
            assertThat(group.consumers()).extracting(ConsumerHandle::id).containsExactly("consumer-0", "consumer-2");
            // A supplied id is honored, and the assigned ids skip it.
            assertThat(group.addConsumer(new ConsumerSpec("consumer-3", null, null)).id()).isEqualTo("consumer-3");
            assertThat(group.addConsumer(ConsumerSpec.inheriting()).id()).isEqualTo("consumer-4");
        }
    }

    @Test
    void anAddedConsumerInheritsTheGroupDefaultsAndIsDescribed() {
        try (var simulator = new Simulator("localhost:9092")) {
            simulator.addTopic("orders", 3);
            var group = simulator.addGroup("app", g -> g
                .subscribe("orders")
                .consumerDefaults(c -> c.protocol(Protocol.CLASSIC).assignor("range"))
            );

            // The effective settings of the argument are not trusted.
            var added = group.addConsumer(new ConsumerSpec("", ConsumerOptions.overrides(c -> c.rackId("b")),
                ConsumerSettings.defaults()));

            assertThat(added.protocol()).isEqualTo(Protocol.CLASSIC);
            var described = group.describe().consumers();
            assertThat(described).singleElement().satisfies(consumer -> {
                assertThat(consumer.id()).isEqualTo("consumer-0");
                assertThat(consumer.effective().assignor()).isEqualTo("range");
                assertThat(consumer.effective().rackId()).isEqualTo("b");
            });
            assertThat(simulator.describe().groups()).containsExactly(group.describe());
            // The group is published asynchronously; its own awaitSnapshot skips the snapshots
            // taken before it appears.
            group.awaitSnapshot(s -> s.consumers().size() == 1, TIMEOUT);
        }
    }

    @Test
    void refusesAnInvalidConsumerAndLeavesTheGroupUnchanged() {
        try (var simulator = new Simulator("localhost:9092")) {
            simulator.addTopic("orders", 3);
            simulator.addGroup(new GroupSpec("a-b", List.of("orders"), 45000, 5000, 1000, null, List.of(named("c"))));
            var group = simulator.addGroup("a", g -> g
                .subscribe("orders")
                .consumerDefaults(c -> c.protocol(Protocol.CLASSIC).assignor("range"))
                .consumers(1)
            );

            assertThatThrownBy(() -> group.addConsumer(c -> c.assignor("no-such-assignor")))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.violations())
                    .extracting(Violation::field)
                    .contains("groups[1].consumers[1].overrides.assignor"));
            assertThatThrownBy(() -> group.addConsumer(c -> c.assignor("cooperative-sticky")))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.violations())
                    .extracting(Violation::field).containsExactly("groups[1].consumers"));
            assertThatThrownBy(() -> group.addConsumer(named("consumer-0")))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.violations())
                    .containsExactly(new Violation("groups[1].consumers[1].id", "duplicate consumer 'consumer-0'")));
            // The client id a-b-c is taken by the other group.
            assertThatThrownBy(() -> group.addConsumer(named("b-c")))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.violations())
                    .containsExactly(new Violation(
                        "groups[1].consumers[1].id",
                        "the client id 'a-b-c' is already used by a consumer of group 'a-b'"
                    )));

            assertThat(group.consumers()).extracting(ConsumerHandle::id).containsExactly("consumer-0");
            // A refused consumer does not use up an id.
            assertThat(group.addConsumer(c -> {}).id()).isEqualTo("consumer-1");
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
            failures.put("addGroup", catchThrowable(() -> simulator.addGroup("late", g -> g.subscribe("orders"))));
            failures.put("remove", catchThrowable(() -> simulator.topics().get(0).remove()));
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
            assertThat(failures).hasSize(11).allSatisfy((method, failure) -> assertThat(failure).as(method)
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
        try (
            var simulator = new Simulator("localhost:9092").withClientProperty("security.protocol", "bogus")
                .withListener(received::add)
        ) {
            simulator.addTopic("orders", 3);

            var failure = catchThrowable(simulator::start);

            assertThat(failure).isInstanceOf(SimulationException.class)
                .hasMessageContaining("The simulator failed to start");
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
