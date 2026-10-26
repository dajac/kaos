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
package io.github.dajac.kaos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dajac.kaos.Simulator;
import io.github.dajac.kaos.test.IntegrationTest;
import io.github.dajac.kaos.test.KafkaCluster;
import io.github.dajac.kaos.test.TestThreads;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A full run driven through the HTTP API against a real broker. */
@IntegrationTest
class HttpRunTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** Netty's global executor thread outlives the proxy's event loops by about a second. */
    private static final Duration THREADS_GONE = Duration.ofSeconds(15);

    private KafkaCluster cluster;
    private Admin admin;
    private Set<Thread> threadsBefore;
    private Server server;
    private ApiClient api;

    @BeforeEach
    void setUp(KafkaCluster cluster) {
        this.cluster = cluster;
        this.admin = cluster.createAdmin();
        this.threadsBefore = Thread.getAllStackTraces().keySet();
        var simulator = new Simulator(cluster.bootstrapServers()).withProxyPort(cluster.proxyPort());
        this.server = new Server(simulator).start(0);
        this.api = new ApiClient(server);
    }

    @AfterEach
    void tearDown() {
        server.close();
        admin.close();
    }

    @Test
    void drivesAFullRun() throws Exception {
        var topic = cluster.uniqueName("orders");
        var extraTopic = cluster.uniqueName("extra");
        var groupId = cluster.uniqueName("http");
        var group = "/api/v1/simulator/groups/" + groupId;

        // Create and start.
        var start = api.post("/api/v1/simulator", """
            {"topics": [{"name": "%s", "partitions": 4}],
             "producers": [{"topics": ["%s"], "ratePerPartition": 10}],
             "groups": [{"id": "%s", "subscribedTopics": ["%s"], "heartbeatIntervalMs": 1000,
                         "consumerDefaults": {"pollDelayMs": 100},
                         "consumers": [{}, {}]}]}
            """.formatted(topic, topic, groupId, topic));
        assertThat(start.statusCode()).as(start.body()).isEqualTo(202);
        assertThat(ApiClient.json(start).get("phase").asText()).isEqualTo("STARTING");

        awaitSnapshot(s -> phase(s).equals("RUNNING") && allAssigned(consumers(s, groupId), 4, 2));
        var described = api.getJson("/api/v1/simulator");
        assertThat(described.get("phase").asText()).isEqualTo("RUNNING");
        assertThat(described.get("lastError").asText()).isEmpty();
        var groupSpec = described.get("spec").get("groups").get(0);
        assertThat(groupSpec.get("consumerDefaults").get("pollDelayMs").asInt()).isEqualTo(100);
        assertThat(groupSpec.get("consumerDefaults").get("maxPollIntervalMs").asInt()).isEqualTo(300_000);
        assertThat(groupSpec.get("consumers")).extracting(c -> c.get("id").asText())
            .containsExactly("consumer-0", "consumer-1");

        // A second start is refused, and so is removing a topic in use.
        assertThat(api.post("/api/v1/simulator", "{}").statusCode()).isEqualTo(409);
        assertThat(api.delete("/api/v1/simulator/topics/" + topic).statusCode()).isEqualTo(409);
        // Unknown resources.
        assertThat(api.put(group + "/consumers/nobody/faults/freeze").statusCode()).isEqualTo(404);
        assertThat(api.put(group + "/consumers/consumer-0/faults/unknown").statusCode()).isEqualTo(404);
        assertThat(api.delete("/api/v1/simulator/groups/nothing").statusCode()).isEqualTo(404);
        // An invalid dynamic add is refused before it runs.
        assertThat(api.post("/api/v1/simulator/topics", "{\"name\": \"%s\"}".formatted(topic)).statusCode())
            .isEqualTo(400);

        // Faults.
        assertThat(api.put(group + "/consumers/consumer-0/faults/freeze").statusCode()).isEqualTo(204);
        awaitSnapshot(s -> {
            var consumer = consumer(s, groupId, "consumer-0");
            return texts(consumer.get("faults")).equals(List.of("freeze"))
                && consumer.get("status").asText().equals("FROZEN");
        });
        assertThat(api.delete(group + "/consumers/consumer-0/faults").statusCode()).isEqualTo(204);
        awaitSnapshot(s -> consumer(s, groupId, "consumer-0").get("faults").isEmpty());

        // Leave and join.
        assertThat(api.post(group + "/consumers/consumer-1/actions", "{\"type\": \"leave\"}").statusCode())
            .isEqualTo(204);
        awaitSnapshot(s -> !consumer(s, groupId, "consumer-1").get("subscribed").asBoolean());
        assertThat(api.post(group + "/consumers/consumer-1/actions", "{\"type\": \"join\"}").statusCode())
            .isEqualTo(204);
        awaitSnapshot(s -> allAssigned(consumers(s, groupId), 4, 2));

        // Switch the protocol of a consumer.
        var switched = api.post(
            group + "/consumers/consumer-1/actions", "{\"type\": \"switch-protocol\", \"protocol\": \"classic\"}"
        );
        assertThat(switched.statusCode()).isEqualTo(202);
        awaitSnapshot(s -> consumer(s, groupId, "consumer-1").get("protocol").asText().equals("classic"));

        // Add a consumer, then remove it.
        assertThat(api.post(group + "/consumers", "{\"overrides\": {\"rackId\": \"b\"}}").statusCode()).isEqualTo(202);
        awaitSnapshot(s -> consumers(s, groupId).size() == 3);
        assertThat(api.delete(group + "/consumers/consumer-2").statusCode()).isEqualTo(202);
        awaitSnapshot(s -> consumers(s, groupId).size() == 2);

        // Pause and resume the producer.
        assertThat(api.put("/api/v1/simulator/producers/producer-0/state", "{\"paused\": true}").statusCode())
            .isEqualTo(204);
        awaitSnapshot(s -> s.get("producers").get(0).get("state").asText().equals("PAUSED"));
        assertThat(api.put("/api/v1/simulator/producers/producer-0/state", "{\"paused\": false}").statusCode())
            .isEqualTo(204);

        // Add a topic, a producer and a group on it, then remove them.
        assertThat(api.post("/api/v1/simulator/topics", "{\"name\": \"%s\", \"partitions\": 1}".formatted(extraTopic))
            .statusCode()).isEqualTo(202);
        awaitSnapshot(s -> texts(s.get("topics"), "name").contains(extraTopic));
        assertThat(
            api.post("/api/v1/simulator/producers", "{\"id\": \"extra\", \"topics\": [\"%s\"]}".formatted(extraTopic))
                .statusCode())
            .isEqualTo(202);
        assertThat(api
            .post("/api/v1/simulator/groups", "{\"id\": \"%s-2\", \"subscribedTopics\": [\"%s\"], \"consumers\": [{}]}"
                .formatted(groupId, extraTopic))
            .statusCode()).isEqualTo(202);
        awaitSnapshot(s -> s.get("groups").size() == 2 && s.get("producers").size() == 2);
        assertThat(api.delete(group + "-2").statusCode()).isEqualTo(202);
        assertThat(api.delete("/api/v1/simulator/producers/extra").statusCode()).isEqualTo(202);
        // The checks of a request see the components as they are, not the operations still queued.
        assertThat(api.delete("/api/v1/simulator/topics/" + extraTopic).statusCode()).isEqualTo(409);
        awaitSnapshot(s -> s.get("groups").size() == 1 && s.get("producers").size() == 1);
        assertThat(api.delete("/api/v1/simulator/topics/" + extraTopic).statusCode()).isEqualTo(202);
        awaitSnapshot(s -> s.get("groups").size() == 1 && !texts(s.get("topics"), "name").contains(extraTopic));

        // A failed operation surfaces as lastError and leaves the simulator running.
        var existing = cluster.uniqueName("existing");
        admin.createTopics(List.of(new NewTopic(existing, 1, (short) 1))).all().get();
        cluster.awaitTopic(admin, existing);
        assertThat(api.post("/api/v1/simulator/topics", "{\"name\": \"%s\"}".formatted(existing)).statusCode())
            .isEqualTo(202);
        await().atMost(TIMEOUT).untilAsserted(
            () -> assertThat(api.getJson("/api/v1/simulator").get("lastError").asText()).contains(existing)
        );
        assertThat(api.getJson("/api/v1/simulator").get("phase").asText()).isEqualTo("RUNNING");

        // Stop.
        var stop = api.delete("/api/v1/simulator");
        assertThat(stop.statusCode()).isEqualTo(202);
        assertThat(ApiClient.json(stop).get("phase").asText()).isEqualTo("STOPPING");
        awaitSnapshot(s -> phase(s).equals("IDLE"));
        var stopped = api.getJson("/api/v1/simulator");
        assertThat(stopped.get("phase").asText()).isEqualTo("IDLE");
        assertThat(stopped.get("lastError").asText()).isEmpty();
        assertThat(stopped.get("spec").get("topics")).isEmpty();
        assertThat(admin.listTopics().names().get()).doesNotContain(topic, extraTopic).contains(existing);
        assertThat(api.delete("/api/v1/simulator").statusCode()).isEqualTo(204);

        server.close();
        await().atMost(THREADS_GONE).untilAsserted(
            () -> assertThat(TestThreads.startedSince(threadsBefore)).noneMatch(name -> name.startsWith("kaos-"))
        );
    }

    private JsonNode awaitSnapshot(Predicate<JsonNode> condition) {
        return await().atMost(TIMEOUT)
            .until(() -> api.getJson("/api/v1/simulator/snapshot"), condition::test);
    }

    private static String phase(JsonNode snapshot) {
        return snapshot.get("phase").asText();
    }

    private static JsonNode groupSnapshot(JsonNode snapshot, String groupId) {
        return stream(snapshot.get("groups")).filter(g -> g.get("id").asText().equals(groupId)).findFirst()
            .orElseThrow();
    }

    private static List<JsonNode> consumers(JsonNode snapshot, String groupId) {
        return stream(groupSnapshot(snapshot, groupId).get("consumers")).toList();
    }

    private static JsonNode consumer(JsonNode snapshot, String groupId, String id) {
        return consumers(snapshot, groupId).stream().filter(c -> c.get("id").asText().equals(id)).findFirst()
            .orElseThrow();
    }

    /** Whether the consumers are stable and hold all the partitions of the topic between them. */
    private static boolean allAssigned(List<JsonNode> consumers, int partitions, int count) {
        return consumers.size() == count
            && consumers.stream().allMatch(c -> c.get("status").asText().equals("STABLE"))
            && consumers.stream().mapToInt(c -> c.get("partitions").size()).sum() == partitions;
    }

    private static List<String> texts(JsonNode array) {
        return stream(array).map(JsonNode::asText).toList();
    }

    private static List<String> texts(JsonNode array, String field) {
        return stream(array).map(node -> node.get(field).asText()).toList();
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }
}
