package io.github.dajac.kaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FaultTest {

    @Test
    void faultsAreValues() {
        assertThat(Fault.freeze()).isEqualTo(Fault.freeze()).isNotEqualTo(Fault.networkIsolation());
        assertThat(Fault.networkIsolation()).isEqualTo(Fault.networkIsolation());
        assertThat(Fault.connectionReset()).isEqualTo(Fault.connectionReset());
    }

    @Test
    void idsRoundTrip() {
        assertThat(Fault.all()).extracting(Fault::id)
            .containsExactly("freeze", "network-isolation", "connection-reset");
        for (var fault : Fault.all()) {
            assertThat(Fault.of(fault.id())).isEqualTo(fault);
        }
    }

    @Test
    void unknownFaultIsRejected() {
        assertThatThrownBy(() -> Fault.of("meteor")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSwitchOverTheSealedTypeIsExhaustive() {
        for (var fault : Fault.all()) {
            var where = switch (fault) {
                case Fault.Freeze f -> "runner";
                case Fault.NetworkIsolation f -> "proxy";
                case Fault.ConnectionReset f -> "proxy";
            };
            assertThat(where).isNotEmpty();
        }
    }

    @Test
    void protocolIdsRoundTrip() {
        assertThat(Protocol.CONSUMER.id()).isEqualTo("consumer");
        assertThat(Protocol.CLASSIC.id()).isEqualTo("classic");
        assertThat(Protocol.of("classic")).isEqualTo(Protocol.CLASSIC);
        assertThat(Protocol.of("CONSUMER")).isEqualTo(Protocol.CONSUMER);
        assertThatThrownBy(() -> Protocol.of("share")).isInstanceOf(IllegalArgumentException.class);
    }
}
