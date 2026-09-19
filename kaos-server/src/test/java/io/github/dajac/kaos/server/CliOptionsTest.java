package io.github.dajac.kaos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CliOptionsTest {

    @Test
    void portDefaultsTo8081() {
        CliOptions options = CliOptions.parse(new String[] {"--bootstrap-server", "localhost:9092"});

        assertThat(options).isEqualTo(new CliOptions("localhost:9092", 8081));
    }

    @Test
    void parsesAllOptionsInAnyOrder() {
        CliOptions options = CliOptions.parse(new String[] {"--port", "0", "--bootstrap-server", "broker:9092"});

        assertThat(options).isEqualTo(new CliOptions("broker:9092", 0));
    }

    @Test
    void bootstrapServerIsRequired() {
        assertThatThrownBy(() -> CliOptions.parse(new String[] {}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--bootstrap-server");
    }

    @Test
    void rejectsAnOptionWithoutValue() {
        assertThatThrownBy(() -> CliOptions.parse(new String[] {"--bootstrap-server"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing value");
    }

    @Test
    void rejectsAnUnknownOption() {
        assertThatThrownBy(() -> CliOptions.parse(new String[] {"--bootstrap-server", "x", "--nope"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--nope");
    }

    @Test
    void rejectsAnInvalidPort() {
        assertThatThrownBy(() -> CliOptions.parse(new String[] {"--bootstrap-server", "x", "--port", "abc"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid port");
        assertThatThrownBy(() -> CliOptions.parse(new String[] {"--bootstrap-server", "x", "--port", "70000"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("out of range");
    }
}
