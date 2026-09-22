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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.github.dajac.kaos.Simulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Drives the command the way {@link Main#main(String[])} does, without {@code System.exit}.
 */
class MainTest {

    private final Main command = new Main();
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @AfterEach
    void stopServer() {
        if (command.server() != null) {
            command.server().close();
        }
    }

    @Test
    void helpPrintsTheUsageAndStartsNothing() {
        assertThat(execute("--help")).isEqualTo(Main.EXIT_OK);

        assertThat(out.toString())
            .contains("Usage: kaos")
            .contains("--bootstrap-server", "localhost:9092")
            .contains("--port", "8081")
            .contains("--proxy-port", "19092")
            .contains("--help", "--version");
        assertThat(command.server()).isNull();
    }

    @Test
    void versionPrintsTheBuildVersionAndStartsNothing() {
        assertThat(execute("--version")).isEqualTo(Main.EXIT_OK);

        assertThat(out.toString().trim()).isEqualTo("Kaos " + Version.read());
        assertThat(command.server()).isNull();
    }

    @Test
    void anInvalidCommandLinePrintsTheErrorAndTheUsage() {
        assertThat(execute("--nope")).isEqualTo(Main.EXIT_USAGE);

        assertThat(err.toString()).contains("--nope").contains("Usage: kaos");
        assertThat(command.server()).isNull();
    }

    @Test
    void anOutOfRangeProxyPortIsAUsageError() {
        assertThat(execute("--proxy-port", "0")).isEqualTo(Main.EXIT_USAGE);
        assertThat(execute("--proxy-port", "65536")).isEqualTo(Main.EXIT_USAGE);

        assertThat(err.toString()).contains("--proxy-port").contains("out of range (1-65535)");
        assertThat(command.server()).isNull();
    }

    @Test
    void startsTheServerOnTheRequestedPort() throws Exception {
        assertThat(execute("--bootstrap-server", "x", "--port", "0")).isEqualTo(Main.EXIT_OK);

        assertThat(command.server()).isNotNull();
        var uri = URI.create("http://localhost:" + command.server().port() + "/api/v1/meta");
        HttpRequest request = HttpRequest.newBuilder(uri).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void aBusyPortIsAStartupFailure() {
        try (Server other = new Server(new Simulator("localhost:9092")).start(0)) {
            assertThat(execute("--port", String.valueOf(other.port()))).isEqualTo(Main.EXIT_STARTUP_FAILURE);

            assertThat(err.toString()).contains("Cannot start the HTTP server on port " + other.port());
            assertThat(command.server()).isNull();
        }
    }

    private int execute(String... args) {
        return new CommandLine(command)
            .setOut(new PrintWriter(out, true))
            .setErr(new PrintWriter(err, true))
            .execute(args);
    }
}
