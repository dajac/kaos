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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import io.github.dajac.kaos.test.IntegrationTest;
import io.github.dajac.kaos.test.KafkaCluster;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.GroupListing;
import org.junit.jupiter.api.Test;

/**
 * Runs the shaded jar as a separate process and drives a simulator through HTTP, so that a class
 * missing from the runtime classpath of the jar fails the build. Failsafe passes the path of the
 * jar in the system property {@code kaos.test.jar}.
 *
 * <p>The process is destroyed when the test ends, and by a JVM shutdown hook if the test JVM is
 * stopped while the test runs.
 */
@IntegrationTest
class ShadedJarTest {

    private static final String JAR_PROPERTY = "kaos.test.jar";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Pattern LISTENING = Pattern.compile("listening on (http://localhost:\\d+)");

    @Test
    void startsAndStopsASimulator(KafkaCluster cluster) throws Exception {
        var jar = Path.of(System.getProperty(JAR_PROPERTY, "")).toAbsolutePath();
        assertThat(jar).as("the shaded jar (system property %s)", JAR_PROPERTY).isRegularFile();
        var log = Files.createTempFile("kaos-shaded-jar", ".log");
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(
            java, "-jar", jar.toString(),
            "--bootstrap-server", cluster.bootstrapServers(),
            "--port", "0",
            "--proxy-port", String.valueOf(cluster.proxyPort())
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        var killer = new Thread(process::destroyForcibly, "shaded-jar-killer");
        Runtime.getRuntime().addShutdownHook(killer);

        try (Admin admin = cluster.createAdmin()) {
            var base = await("the server to listen").atMost(TIMEOUT).until(() -> {
                assertThat(process.isAlive()).as("the process is alive; its output:%n%s", read(log)).isTrue();
                var matcher = LISTENING.matcher(read(log));
                return matcher.find() ? matcher.group(1) : null;
            }, url -> url != null);
            var api = new ApiClient(base);

            var topic = cluster.uniqueName("jar");
            var groupId = cluster.uniqueName("jar");
            var start = api.post("/api/v1/simulator", """
                {"topics": [{"name": "%s", "partitions": 2}],
                 "producers": [{"topics": ["%s"]}],
                 "groups": [{"id": "%s", "subscribedTopics": ["%s"], "consumers": [{}]}]}
                """.formatted(topic, topic, groupId, topic));
            assertThat(start.statusCode()).as(start.body()).isEqualTo(202);

            await("the simulator to run").atMost(TIMEOUT)
                .failFast(
                    "the start failed",
                    () -> assertThat(api.getJson("/api/v1/simulator").get("lastError").asText()).isEmpty()
                )
                .untilAsserted(() -> {
                    var snapshot = api.getJson("/api/v1/simulator/snapshot");
                    assertThat(snapshot.get("phase").asText()).isEqualTo("RUNNING");
                    var consumer = snapshot.get("groups").get(0).get("consumers").get(0);
                    assertThat(consumer.get("status").asText()).isEqualTo("STABLE");
                    assertThat(consumer.get("partitions")).hasSize(2);
                });

            assertThat(api.delete("/api/v1/simulator").statusCode()).isEqualTo(202);
            await("the simulator to stop").atMost(TIMEOUT).untilAsserted(() -> {
                var described = api.getJson("/api/v1/simulator");
                assertThat(described.get("phase").asText()).isEqualTo("IDLE");
                assertThat(described.get("lastError").asText()).isEmpty();
            });
            assertThat(admin.listTopics().names().get()).doesNotContain(topic);
            assertThat(admin.listGroups().all().get()).extracting(GroupListing::groupId).doesNotContain(groupId);
        } catch (AssertionError | RuntimeException e) {
            throw new AssertionError(e.getMessage() + "\nOutput of the jar:\n" + read(log), e);
        } finally {
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
            Runtime.getRuntime().removeShutdownHook(killer);
            Files.deleteIfExists(log);
        }
    }

    private static String read(Path log) {
        try {
            return Files.readString(log);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
