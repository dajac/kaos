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
    }

    @Test
    void parsesAllOptionsInAnyOrder() {
        CliOptions options = parse("--port", "0", "--bootstrap-server", "broker:9092");

        assertThat(options.bootstrapServer()).isEqualTo("broker:9092");
        assertThat(options.port()).isZero();
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

    private static CliOptions parse(String... args) {
        CliOptions options = new CliOptions();
        new CommandLine(options).parseArgs(args);
        return options;
    }
}
