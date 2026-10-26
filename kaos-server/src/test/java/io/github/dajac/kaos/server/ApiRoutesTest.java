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

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dajac.kaos.Simulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The routes of the HTTP API against a simulator that never runs: the idle state, the problem
 * details, and the 400, 404 and 409 answers that need no cluster.
 */
class ApiRoutesTest {

    /** Its bootstrap server does not resolve, so a start fails at once. */
    private static final String UNRESOLVABLE = "kaos-test.invalid:9092";

    private static final String VALID_SPEC = """
        {"topics": [{"name": "orders", "partitions": 3}],
         "producers": [{"topics": ["orders"]}],
         "groups": [{"id": "app", "subscribedTopics": ["orders"], "consumers": [{}, {"id": "mine"}]}]}
        """;

    private Server server;
    private ApiClient api;

    @BeforeEach
    void startServer() {
        server = new Server(new Simulator(UNRESOLVABLE)).start(0);
        api = new ApiClient(server);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void anIdleSimulatorHasAnEmptySpecAndSnapshot() {
        var simulator = api.getJson("/api/v1/simulator");
        assertThat(simulator.get("phase").asText()).isEqualTo("IDLE");
        assertThat(simulator.get("lastError").asText()).isEmpty();
        assertThat(simulator.has("warnings")).isFalse();
        assertThat(simulator.get("spec").get("topics")).isEmpty();
        assertThat(simulator.get("spec").get("producers")).isEmpty();
        assertThat(simulator.get("spec").get("groups")).isEmpty();

        var snapshot = api.getJson("/api/v1/simulator/snapshot");
        assertThat(snapshot.get("revision").asLong()).isZero();
        assertThat(snapshot.get("phase").asText()).isEqualTo("IDLE");
        assertThat(snapshot.get("groups")).isEmpty();
    }

    @Test
    void deletingAnIdleSimulatorIsANoOp() {
        var response = api.delete("/api/v1/simulator");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(api.getJson("/api/v1/simulator").get("phase").asText()).isEqualTo("IDLE");
    }

    @Test
    void anInvalidSpecIs400WithEveryViolation() {
        var response = api.post("/api/v1/simulator", """
            {"topics": [{"name": "orders", "partitions": 0}],
             "groups": [{"id": "app", "subscribedTopics": ["unknown"],
                         "consumerDefaults": {"protocol": "classic", "assignor": "uniform"}}]}
            """);

        var problem = assertProblem(response, 400);
        assertThat(problem.get("violations")).extracting(v -> v.get("field").asText())
            .contains("topics[0].partitions", "groups[0].subscribedTopics[0]", "groups[0].consumerDefaults.assignor");
        assertThat(problem.get("violations")).allSatisfy(v -> assertThat(v.get("message").asText()).isNotBlank());
        // Nothing was started.
        assertThat(api.getJson("/api/v1/simulator").get("phase").asText()).isEqualTo("IDLE");
    }

    @Test
    void aClassicOnlyOverrideOnAConsumerOfTheConsumerProtocolIs400() {
        var response = api.post("/api/v1/simulator", """
            {"topics": [{"name": "orders", "partitions": 1}],
             "groups": [{"id": "app", "subscribedTopics": ["orders"],
                         "consumers": [{"overrides": {"protocol": "consumer", "sessionTimeoutMs": 10000}}]}]}
            """);

        var problem = assertProblem(response, 400);
        assertThat(problem.get("violations")).singleElement().satisfies(
            v -> assertThat(v.get("field").asText()).isEqualTo("groups[0].consumers[0].overrides.sessionTimeoutMs")
        );
    }

    @Test
    void anUnknownPropertyIs400WithItsPath() {
        var response = api.post("/api/v1/simulator", """
            {"groups": [{"id": "app", "subscribedTopics": ["orders"], "consumers": [{"overrides": {"bogus": 1}}]}]}
            """);

        var problem = assertProblem(response, 400);
        assertThat(problem.get("violations")).singleElement().satisfies(v -> {
            assertThat(v.get("field").asText()).isEqualTo("groups[0].consumers[0].overrides.bogus");
            assertThat(v.get("message").asText()).isEqualTo("is not a known property");
        });
    }

    @Test
    void aNullListElementIs400() {
        var response = api.post("/api/v1/simulator", """
            {"topics": [null]}
            """);

        var problem = assertProblem(response, 400);
        assertThat(problem.get("violations")).singleElement().satisfies(v -> {
            assertThat(v.get("field").asText()).isEqualTo("topics[0]");
            assertThat(v.get("message").asText()).isEqualTo("must not be null");
        });
    }

    @Test
    void anUnknownProtocolIs400() {
        var response = api.post("/api/v1/simulator", """
            {"groups": [{"id": "app", "consumerDefaults": {"protocol": "eager"}}]}
            """);

        var problem = assertProblem(response, 400);
        assertThat(problem.get("violations")).singleElement()
            .satisfies(v -> assertThat(v.get("field").asText()).isEqualTo("groups[0].consumerDefaults.protocol"));
    }

    @Test
    void aMalformedOrMissingBodyIs400() {
        var malformed = assertProblem(api.post("/api/v1/simulator", "{\"topics\": ["), 400);
        assertThat(malformed.get("detail").asText()).startsWith("The request body is not valid JSON");
        assertThat(malformed.has("violations")).isFalse();

        var missing = assertProblem(api.post("/api/v1/simulator", ""), 400);
        assertThat(missing.get("detail").asText()).isEqualTo("The request body is missing");

        assertProblem(api.post("/api/v1/simulator", "null"), 400);
        assertProblem(api.post("/api/v1/simulator", "{} {}"), 400);
    }

    @Test
    void anUnknownRouteIs404() {
        var problem = assertProblem(api.get("/api/v1/unknown"), 404);
        assertThat(problem.get("title").asText()).isEqualTo("Not Found");
        assertThat(problem.get("detail").asText()).contains("/api/v1/unknown");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("dynamicRoutes")
    void dynamicRoutesAre409WhenIdle(String method, String path, String body) {
        var response = switch (method) {
            case "POST" -> api.post(path, body);
            case "PUT" -> body == null ? api.put(path) : api.put(path, body);
            case "DELETE" -> api.delete(path);
            default -> throw new IllegalArgumentException(method);
        };

        var problem = assertProblem(response, 409);
        assertThat(problem.get("detail").asText()).isEqualTo("The simulator is not running");
    }

    static Stream<Arguments> dynamicRoutes() {
        var consumer = "/api/v1/simulator/groups/app/consumers/consumer-0";
        return Stream.of(
            Arguments.of("POST", "/api/v1/simulator/topics", "{\"name\": \"extra\"}"),
            Arguments.of("DELETE", "/api/v1/simulator/topics/orders", null),
            Arguments.of("POST", "/api/v1/simulator/producers", "{\"topics\": [\"orders\"]}"),
            Arguments.of("DELETE", "/api/v1/simulator/producers/producer-0", null),
            Arguments.of("PUT", "/api/v1/simulator/producers/producer-0/state", "{\"paused\": true}"),
            Arguments.of("POST", "/api/v1/simulator/groups", "{\"id\": \"other\", \"subscribedTopics\": [\"orders\"]}"),
            Arguments.of("DELETE", "/api/v1/simulator/groups/app", null),
            Arguments.of("POST", "/api/v1/simulator/groups/app/consumers", "{}"),
            Arguments.of("DELETE", consumer, null),
            Arguments.of("PUT", consumer + "/faults/freeze", null),
            Arguments.of("DELETE", consumer + "/faults/freeze", null),
            Arguments.of("DELETE", consumer + "/faults", null),
            Arguments.of("POST", consumer + "/actions", "{\"type\": \"join\"}"),
            Arguments.of("POST", consumer + "/actions", "{\"type\": \"leave\"}"),
            Arguments.of("POST", consumer + "/actions", "{\"type\": \"switch-protocol\", \"protocol\": \"classic\"}")
        );
    }

    @Test
    void aFailedStartSurfacesAsLastErrorAndLeavesTheSimulatorEmpty() {
        var response = api.post("/api/v1/simulator", VALID_SPEC);

        assertThat(response.statusCode()).isEqualTo(202);
        var accepted = ApiClient.json(response);
        assertThat(accepted.get("phase").asText()).isEqualTo("STARTING");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var simulator = api.getJson("/api/v1/simulator");
            assertThat(simulator.get("phase").asText()).isEqualTo("IDLE");
            assertThat(simulator.get("lastError").asText()).isNotEmpty();
        });
        var simulator = api.getJson("/api/v1/simulator");
        assertThat(simulator.get("spec").get("topics")).isEmpty();
        assertThat(simulator.get("spec").get("groups")).isEmpty();
        assertThat(api.getJson("/api/v1/simulator/snapshot").get("lastError").asText())
            .isEqualTo(simulator.get("lastError").asText());

        // A stop that finds nothing to stop keeps the error.
        assertThat(api.delete("/api/v1/simulator").statusCode()).isEqualTo(204);
        assertThat(api.getJson("/api/v1/simulator").get("lastError").asText()).isNotEmpty();
    }

    private static JsonNode assertProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(ProblemHandler.CONTENT_TYPE);
        var problem = ApiClient.json(response);
        assertThat(problem.get("type").asText()).isEqualTo("about:blank");
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("title").asText()).isNotBlank();
        assertThat(problem.get("detail").asText()).isNotBlank();
        return problem;
    }
}
