package io.github.dajac.kaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

import java.util.List;

class SimulatorValidateTest {

    @Test
    void aValidSpecPasses() {
        SimulatorSpec spec = new SimulatorSpec(
            List.of(TopicSpec.of("orders")),
            List.of(ProducerSpec.of(p -> p.topics("orders"))),
            List.of(GroupSpec.of("next", g -> g.subscribe("orders").consumers(3))));

        assertThatCode(() -> Simulator.validate(spec)).doesNotThrowAnyException();
    }

    @Test
    void anInvalidSpecThrowsWithEveryViolation() {
        SimulatorSpec spec = new SimulatorSpec(
            List.of(new TopicSpec("orders", 101)),
            List.of(),
            List.of(GroupSpec.of("legacy", g -> g
                .subscribe("missing")
                .consumerDefaults(c -> c.protocol(Protocol.CLASSIC).assignor("uniform")))));

        InvalidConfigException e =
            catchThrowableOfType(InvalidConfigException.class, () -> Simulator.validate(spec));

        assertThat(e.violations()).extracting(Violation::field).containsExactly(
            "topics[0].partitions",
            "groups[0].subscribedTopics[0]",
            "groups[0].consumerDefaults.assignor");
        assertThat(e).isInstanceOf(SimulationException.class)
            .hasMessageStartingWith("Invalid configuration: topics[0].partitions: must be between 1 and 100, got 101;");
    }

    @Test
    void anExceptionNeedsAtLeastOneViolation() {
        assertThatThrownBy(() -> new InvalidConfigException(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
