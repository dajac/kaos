package io.github.dajac.kaos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidNullException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.ConsumerSettings;
import io.github.dajac.kaos.ConsumerSpec;
import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.ProducerSpec;
import io.github.dajac.kaos.Protocol;
import io.github.dajac.kaos.SimulatorSpec;
import io.github.dajac.kaos.TopicSpec;
import io.github.dajac.kaos.server.dto.GroupRequest;
import io.github.dajac.kaos.server.dto.SimulatorRequest;
import org.junit.jupiter.api.Test;

/** The binding of the request bodies and the JSON form of the core types. */
class JsonTest {

    private final ObjectMapper mapper = Json.mapper();

    @Test
    void aPartialConsumerDefaultsTakesTheDefaultsForTheMissingValues() throws Exception {
        var group = mapper.readValue(
            """
                {"id": "app", "subscribedTopics": ["orders"], "consumerDefaults": {"protocol": "CLASSIC", "pollTimeoutMs": 5}}
                """,
            GroupRequest.class).toSpec();

        assertThat(group.consumerDefaults()).isEqualTo(new ConsumerSettings(
            Protocol.CLASSIC, "", false, "", 5, ConfigMetadata.POLL_DELAY_MS.intDefault(),
            ConfigMetadata.MAX_POLL_INTERVAL_MS.intDefault()
        ));
        assertThat(group.sessionTimeoutMs()).isEqualTo(ConfigMetadata.SESSION_TIMEOUT_MS.intDefault());
        assertThat(group.heartbeatIntervalMs()).isEqualTo(ConfigMetadata.HEARTBEAT_INTERVAL_MS.intDefault());
        assertThat(group.assignmentIntervalMs()).isEqualTo(ConfigMetadata.ASSIGNMENT_INTERVAL_MS.intDefault());
    }

    @Test
    void missingTopicAndProducerValuesTakeTheDefaults() throws Exception {
        var spec = mapper.readValue("""
            {"topics": [{"name": "orders"}], "producers": [{"topics": ["orders"]}]}
            """, SimulatorRequest.class).toSpec();

        assertThat(spec.topics()).containsExactly(new TopicSpec("orders", ConfigMetadata.PARTITIONS.intDefault()));
        assertThat(spec.producers()).containsExactly(
            new ProducerSpec("", List.of("orders"), ConfigMetadata.RATE_PER_PARTITION.doubleDefault())
        );
        assertThat(spec.groups()).isEmpty();
    }

    @Test
    void consumerOverridesBindAsOptionals() throws Exception {
        var consumer = mapper.readValue("""
            {"id": "c", "overrides": {"protocol": "classic", "pollDelayMs": 10, "rackId": null}}
            """, ConsumerSpec.class);

        assertThat(consumer.overrides().protocol()).contains(Protocol.CLASSIC);
        assertThat(consumer.overrides().pollDelayMs()).hasValue(10);
        assertThat(consumer.overrides().rackId()).isEmpty();
        assertThat(consumer.overrides().assignor()).isEmpty();
    }

    @Test
    void theSpecOfTheSimulatorBindsBackToTheSameSpec() throws Exception {
        var group = GroupSpec.of("app", g -> g
            .subscribe("orders")
            .consumerDefaults(c -> c.protocol(Protocol.CLASSIC).assignor("range"))
            .consumer(c -> c.rackId("a").protocol(Protocol.CONSUMER).assignor("uniform"))
            .consumers(2));
        var spec = new SimulatorSpec(
            List.of(new TopicSpec("orders", 3)),
            List.of(ProducerSpec.of(p -> p.topics("orders").ratePerPartition(5))),
            List.of(group)
        );

        var json = mapper.writeValueAsString(spec);
        var bound = mapper.readValue(json, SimulatorRequest.class).toSpec();

        assertThat(bound).isEqualTo(spec);
        // An override that is not set is left out, and a protocol is its lower-case id.
        var tree = mapper.readTree(json);
        var overrides = tree.get("groups").get(0).get("consumers").get(0).get("overrides");
        assertThat(overrides.fieldNames()).toIterable().containsExactlyInAnyOrder("protocol", "assignor", "rackId");
        assertThat(overrides.get("protocol").asText()).isEqualTo("consumer");
        assertThat(tree.get("groups").get(0).get("consumers").get(1).get("overrides").isEmpty()).isTrue();
    }

    @Test
    void aFaultIsWrittenAsItsId() throws Exception {
        assertThat(mapper.writeValueAsString(List.of(Fault.freeze(), Fault.networkIsolation())))
            .isEqualTo("[\"freeze\",\"network-isolation\"]");
    }

    @Test
    void unknownPropertiesAndNullElementsAreRefused() {
        assertThatThrownBy(() -> mapper.readValue("{\"bogus\": 1}", SimulatorRequest.class))
            .isInstanceOf(UnrecognizedPropertyException.class);
        assertThatThrownBy(
            () -> mapper.readValue("{\"groups\": [{\"subscribedTopics\": [null]}]}", SimulatorRequest.class))
            .isInstanceOf(InvalidNullException.class);
        assertThatThrownBy(
            () -> mapper.readValue("{\"topics\": [{\"name\": \"t\", \"partitions\": 1.5}]}", SimulatorRequest.class))
            .isInstanceOf(MismatchedInputException.class);
    }
}
