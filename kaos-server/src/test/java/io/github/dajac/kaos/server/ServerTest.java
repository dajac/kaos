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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.Simulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerTest {

    private Server server;
    private ApiClient api;

    @BeforeEach
    void startServer() {
        server = new Server(new Simulator("localhost:9092")).start(0);
        api = new ApiClient(server);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void metaReturnsTheBuildVersionAndTheConfigMetadata() {
        var response = api.get("/api/v1/meta");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");

        var body = ApiClient.json(response);
        assertThat(body.fieldNames()).toIterable()
            .containsExactly("version", "fields", "nameRule", "assignors", "faults");
        // The version comes from the filtered resource, so the Maven placeholder must be resolved.
        assertThat(body.get("version").asText()).isEqualTo(Version.read()).matches("\\d+\\.\\d+\\.\\d+.*");

        var metadata = ConfigMetadata.get();
        assertThat(body.get("fields")).hasSize(metadata.fields().size());
        var partitions = body.get("fields").get(1);
        assertThat(partitions.get("key").asText()).isEqualTo("partitions");
        assertThat(partitions.get("scope").asText()).isEqualTo("TOPIC");
        assertThat(partitions.get("defaultValue").asInt()).isEqualTo(36);
        assertThat(partitions.get("range").get("min").asInt()).isEqualTo(1);
        assertThat(partitions.get("range").get("max").asInt()).isEqualTo(100);
        // An absent range is left out.
        assertThat(body.get("fields").get(2).has("range")).isFalse();
        var protocol = body.get("fields").get(8);
        assertThat(protocol.get("key").asText()).isEqualTo("protocol");
        assertThat(protocol.get("defaultValue").asText()).isEqualTo("consumer");

        assertThat(body.get("nameRule").get("maxLength").asInt()).isEqualTo(249);
        assertThat(body.get("assignors").fieldNames()).toIterable().containsExactlyInAnyOrder("consumer", "classic");
        assertThat(body.get("assignors").get("consumer").get("customAllowed").asBoolean()).isTrue();
        assertThat(body.get("assignors").get("classic").get("names")).extracting(n -> n.asText())
            .containsExactly("range", "roundrobin", "sticky", "cooperative-sticky");
        assertThat(body.get("faults")).extracting(n -> n.asText())
            .containsExactly("freeze", "network-isolation", "connection-reset");
    }

    @Test
    void theRootIsNotServed() {
        assertThat(api.get("/").statusCode()).isEqualTo(404);
    }

    @Test
    void startOnABusyPortFailsAndReleasesTheServer() {
        Set<Thread> before = liveNonDaemonThreads();

        var second = new Server(new Simulator("localhost:9092"));
        assertThatThrownBy(() -> second.start(server.port()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(String.valueOf(server.port()));

        // The failed server leaves no non-daemon thread behind, so it cannot keep the JVM alive.
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(leakedSince(before)).isEmpty());

        // The first server is unaffected, and closing the failed one is harmless.
        assertThat(api.get("/api/v1/meta").statusCode()).isEqualTo(200);
        second.close();
    }

    @Test
    void closeIsIdempotent() {
        server.close();
        server.close();
    }

    private static Set<Thread> liveNonDaemonThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.isAlive() && !t.isDaemon() && !t.getName().startsWith("awaitility"))
            .collect(Collectors.toSet());
    }

    private static Set<Thread> leakedSince(Set<Thread> before) {
        Set<Thread> now = liveNonDaemonThreads();
        now.removeAll(before);
        return now;
    }
}
