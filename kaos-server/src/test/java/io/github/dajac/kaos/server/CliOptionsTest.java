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

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

class CliOptionsTest {

    @Test
    void defaultsToALocalBrokerAndPort8081() {
        CliOptions options = parse();

        assertThat(options.bootstrapServer()).isEqualTo("localhost:9092");
        assertThat(options.port()).isEqualTo(8081);
        assertThat(options.proxyPort()).isEqualTo(19092);
    }

    @Test
    void parsesAllOptionsInAnyOrder() {
        CliOptions options = parse("--proxy-port", "25000", "--port", "0", "--bootstrap-server", "broker:9092");

        assertThat(options.bootstrapServer()).isEqualTo("broker:9092");
        assertThat(options.port()).isZero();
        assertThat(options.proxyPort()).isEqualTo(25000);
    }

    @Test
    void acceptsTheEqualsForm() {
        CliOptions options = parse("--port=9000", "--bootstrap-server=a:9092,b:9092");

        assertThat(options.bootstrapServer()).isEqualTo("a:9092,b:9092");
        assertThat(options.port()).isEqualTo(9000);
    }

    @Test
    void rejectsABlankBootstrapServer() {
        assertThatThrownBy(() -> parse("--bootstrap-server", " "))
            .isInstanceOf(ParameterException.class)
            .hasMessageContaining("--bootstrap-server")
            .hasMessageContaining("must not be blank");
    }

    @Test
    void rejectsAnOptionWithoutValue() {
        assertThatThrownBy(() -> parse("--bootstrap-server"))
            .isInstanceOf(ParameterException.class)
            .hasMessageContaining("--bootstrap-server");
    }

    @Test
    void rejectsAnUnknownOption() {
        assertThatThrownBy(() -> parse("--nope"))
            .isInstanceOf(ParameterException.class)
            .hasMessageContaining("--nope");
    }

    @Test
    void rejectsAnInvalidPort() {
        assertThatThrownBy(() -> parse("--port", "abc"))
            .isInstanceOf(ParameterException.class)
            .hasMessageContaining("--port")
            .hasMessageContaining("not a number");
        assertThatThrownBy(() -> parse("--port", "70000"))
            .isInstanceOf(ParameterException.class)
            .hasMessageContaining("--port")
            .hasMessageContaining("out of range");
    }

    @Test
    void acceptsTheBoundsOfTheProxyPort() {
        assertThat(parse("--proxy-port", "1").proxyPort()).isEqualTo(1);
        assertThat(parse("--proxy-port=65535").proxyPort()).isEqualTo(65535);
    }

    @Test
    void rejectsAnInvalidProxyPort() {
        assertThatThrownBy(() -> parse("--proxy-port", "0"))
            .isInstanceOf(ParameterException.class)
            .hasMessageContaining("--proxy-port")
            .hasMessageContaining("out of range (1-65535)");
        assertThatThrownBy(() -> parse("--proxy-port", "65536"))
            .isInstanceOf(ParameterException.class)
            .hasMessageContaining("--proxy-port")
            .hasMessageContaining("out of range (1-65535)");
        assertThatThrownBy(() -> parse("--proxy-port", "abc"))
            .isInstanceOf(ParameterException.class)
            .hasMessageContaining("--proxy-port")
            .hasMessageContaining("not a number");
    }

    private static CliOptions parse(String... args) {
        CliOptions options = new CliOptions();
        new CommandLine(options).parseArgs(args);
        return options;
    }
}
