package io.github.dajac.kaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

class OptionsTest {

    @Test
    void producerDefaults() {
        ProducerSpec spec = ProducerSpec.of(p -> p.topics("orders"));

        assertThat(spec).isEqualTo(new ProducerSpec("", List.of("orders"), 2.0));
    }

    @Test
    void producerOptions() {
        ProducerSpec spec = ProducerSpec.of(p -> p.id("load").topics("orders", "payments").ratePerPartition(10));

        assertThat(spec).isEqualTo(new ProducerSpec("load", List.of("orders", "payments"), 10.0));
    }

    @Test
    void groupDefaults() {
        GroupSpec spec = GroupSpec.of("next", g -> g.subscribe("orders"));

        assertThat(spec).isEqualTo(new GroupSpec(
            "next", List.of("orders"), 45_000, 5_000, 1_000, ConsumerSettings.defaults(), List.of()));
    }

    @Test
    void groupSettings() {
        GroupSpec spec = GroupSpec.of("next", g -> g
            .subscribe("orders")
            .subscribe("payments")
            .sessionTimeout(Duration.ofSeconds(30))
            .heartbeatInterval(Duration.ofSeconds(3))
            .assignmentInterval(Duration.ofMillis(500)));

        assertThat(spec.subscribedTopics()).containsExactly("orders", "payments");
        assertThat(spec.sessionTimeoutMs()).isEqualTo(30_000);
        assertThat(spec.heartbeatIntervalMs()).isEqualTo(3_000);
        assertThat(spec.assignmentIntervalMs()).isEqualTo(500);
    }

    @Test
    void consumerDefaultsKeepTheTableDefaultsForUnsetValues() {
        GroupSpec spec = GroupSpec.of("legacy", g -> g
            .consumerDefaults(c -> c.protocol(Protocol.CLASSIC).assignor("cooperative-sticky")));

        assertThat(spec.consumerDefaults())
            .isEqualTo(new ConsumerSettings(Protocol.CLASSIC, "cooperative-sticky", false, "", 0, 1_000, 300_000));
    }

    @Test
    void consumersInheritFromTheGroupWhateverTheCallOrder() {
        GroupSpec spec = GroupSpec.of("legacy", g -> g
            .consumer(c -> c.rackId("a"))
            .consumers(2)
            .consumerDefaults(c -> c.protocol(Protocol.CLASSIC).assignor("sticky").pollDelay(Duration.ofMillis(10))));

        assertThat(spec.consumers()).hasSize(3);
        ConsumerSpec first = spec.consumers().get(0);
        assertThat(first.id()).isEmpty();
        assertThat(first.overrides().rackId()).contains("a");
        assertThat(first.overrides().protocol()).isEmpty();
        assertThat(first.effective())
            .isEqualTo(new ConsumerSettings(Protocol.CLASSIC, "sticky", false, "a", 0, 10, 300_000));
        assertThat(spec.consumers().get(1).overrides()).isEqualTo(ConsumerOverrides.none());
        assertThat(spec.consumers().get(1).effective()).isEqualTo(spec.consumerDefaults());
        assertThat(spec.consumers().get(2).effective()).isEqualTo(spec.consumerDefaults());
    }

    @Test
    void everyConsumerOptionBecomesAnOverride() {
        ConsumerSpec spec = ConsumerSpec.of(c -> c
            .protocol(Protocol.CLASSIC)
            .assignor("range")
            .staticMember(true)
            .rackId("b")
            .pollTimeout(Duration.ofMillis(100))
            .pollDelay(Duration.ofSeconds(2))
            .maxPollInterval(Duration.ofMinutes(1)));

        assertThat(spec.overrides()).isEqualTo(new ConsumerOverrides(
            Optional.of(Protocol.CLASSIC),
            Optional.of("range"),
            Optional.of(true),
            Optional.of("b"),
            OptionalInt.of(100),
            OptionalInt.of(2_000),
            OptionalInt.of(60_000)));
        assertThat(spec.effective())
            .isEqualTo(new ConsumerSettings(Protocol.CLASSIC, "range", true, "b", 100, 2_000, 60_000));
    }

    @Test
    void standaloneConsumerResolvesAgainstTheTableDefaults() {
        assertThat(ConsumerSpec.inheriting().effective()).isEqualTo(ConsumerSettings.defaults());
    }

    @Test
    void groupSpecRecomputesTheEffectiveSettingsOfItsConsumers() {
        ConsumerSettings defaults = new ConsumerSettings(Protocol.CLASSIC, "range", true, "z", 1, 2, 3_000);
        ConsumerSpec stale = ConsumerSpec.of(c -> c.rackId("a"));

        GroupSpec spec = new GroupSpec("g", List.of("t"), 45_000, 5_000, 1_000, defaults, List.of(stale));

        assertThat(spec.consumers().get(0).effective())
            .isEqualTo(new ConsumerSettings(Protocol.CLASSIC, "range", true, "a", 1, 2, 3_000));
    }

    @Test
    void negativeConsumerCountIsRejected() {
        assertThatThrownBy(() -> GroupSpec.of("g", g -> g.consumers(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullValuesFailFast() {
        assertThatThrownBy(() -> ConsumerSpec.of(c -> c.assignor(null))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ConsumerSpec.of(c -> c.pollDelay(null))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ProducerSpec.of(p -> p.topics((String) null)))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void absentValuesAreNormalised() {
        assertThat(new SimulatorSpec(null, null, null))
            .isEqualTo(new SimulatorSpec(List.of(), List.of(), List.of()));
        assertThat(new TopicSpec(null, 3)).isEqualTo(new TopicSpec("", 3));
        assertThat(new ProducerSpec(null, null, 1.0)).isEqualTo(new ProducerSpec("", List.of(), 1.0));
        assertThat(new GroupSpec(null, null, 1, 2, 3, null, null))
            .isEqualTo(new GroupSpec("", List.of(), 1, 2, 3, ConsumerSettings.defaults(), List.of()));
        assertThat(new ConsumerSpec(null, null, null)).isEqualTo(ConsumerSpec.inheriting());
        assertThat(new ConsumerOverrides(null, null, null, null, null, null, null))
            .isEqualTo(ConsumerOverrides.none());
        assertThat(new ConsumerSettings(null, null, false, null, 0, 1_000, 300_000))
            .isEqualTo(ConsumerSettings.defaults());
    }

    @Test
    void consumersOfAGroupWithoutDefaultsResolveAgainstTheTableDefaults() {
        GroupSpec spec = new GroupSpec(
            "g", List.of("t"), 45_000, 5_000, 1_000, null, List.of(ConsumerSpec.of(c -> c.rackId("a"))));

        assertThat(spec.consumers().get(0).effective())
            .isEqualTo(new ConsumerSettings(Protocol.CONSUMER, "", false, "a", 0, 1_000, 300_000));
    }

    @Test
    void aSuppliedEffectiveIsKeptOnAStandaloneConsumerAndReplacedInAGroup() {
        ConsumerSettings supplied = new ConsumerSettings(Protocol.CLASSIC, "sticky", true, "z", 1, 2, 3_000);
        ConsumerSpec consumer = new ConsumerSpec("", ConsumerOverrides.none(), supplied);

        assertThat(consumer.effective()).isEqualTo(supplied);
        assertThat(new GroupSpec("g", List.of("t"), 45_000, 5_000, 1_000, null, List.of(consumer))
            .consumers().get(0).effective()).isEqualTo(ConsumerSettings.defaults());
    }

    @Test
    void hugeDurationsSaturate() {
        ConsumerSpec spec = ConsumerSpec.of(c -> c
            .pollDelay(Duration.ofSeconds(Long.MAX_VALUE))
            .pollTimeout(Duration.ofSeconds(Long.MIN_VALUE)));

        assertThat(spec.overrides().pollDelayMs()).hasValue(Integer.MAX_VALUE);
        assertThat(spec.overrides().pollTimeoutMs()).hasValue(Integer.MIN_VALUE);
    }

    @Test
    void specsCopyTheirLists() {
        List<String> topics = new ArrayList<>(List.of("orders"));
        ProducerSpec producer = new ProducerSpec("p", topics, 1.0);
        topics.add("payments");

        assertThat(producer.topics()).containsExactly("orders");
        assertThatThrownBy(() -> producer.topics().add("x")).isInstanceOf(UnsupportedOperationException.class);

        SimulatorSpec spec = new SimulatorSpec(List.of(TopicSpec.of("orders")), List.of(producer), List.of());
        assertThat(spec.topics()).containsExactly(new TopicSpec("orders", 36));
        assertThatThrownBy(() -> spec.groups().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }
}
