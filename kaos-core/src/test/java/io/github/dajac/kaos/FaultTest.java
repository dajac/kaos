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
