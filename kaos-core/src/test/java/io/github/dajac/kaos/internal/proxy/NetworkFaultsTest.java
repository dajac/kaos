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
package io.github.dajac.kaos.internal.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import io.github.dajac.kaos.internal.proxy.NetworkFaults.Mode;
import org.junit.jupiter.api.Test;

class NetworkFaultsTest {

    private final NetworkFaults faults = new NetworkFaults();

    @Test
    void aClientHasNoFaultByDefault() {
        assertThat(faults.modeOf("a")).isNull();
        assertThat(faults.modeOf(null)).isNull();
        assertThat(faults.snapshot()).isEmpty();
    }

    @Test
    void setAppliesAndReplacesTheModeOfOneClient() {
        faults.set("a", Mode.BLACKHOLE);
        assertThat(faults.modeOf("a")).isEqualTo(Mode.BLACKHOLE);
        assertThat(faults.modeOf("b")).isNull();

        faults.set("a", Mode.RESET);
        assertThat(faults.modeOf("a")).isEqualTo(Mode.RESET);
    }

    @Test
    void clearRemovesTheFaultOfOneClient() {
        faults.set("a", Mode.BLACKHOLE);
        faults.set("b", Mode.RESET);

        faults.clear("a");
        faults.clear("unknown");

        assertThat(faults.snapshot()).isEqualTo(Map.of("b", Mode.RESET));
    }

    @Test
    void clearAllRemovesEveryFault() {
        faults.set("a", Mode.BLACKHOLE);
        faults.set("b", Mode.RESET);

        faults.clearAll();

        assertThat(faults.snapshot()).isEmpty();
    }

    @Test
    void snapshotIsAnImmutableCopy() {
        faults.set("a", Mode.BLACKHOLE);
        var snapshot = faults.snapshot();

        faults.clear("a");

        assertThat(snapshot).isEqualTo(Map.of("a", Mode.BLACKHOLE));
        assertThatThrownBy(() -> snapshot.put("b", Mode.RESET)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void instancesAreIndependent() {
        var other = new NetworkFaults();
        faults.set("a", Mode.BLACKHOLE);

        assertThat(other.modeOf("a")).isNull();
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> faults.set(null, Mode.RESET)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> faults.set("a", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> faults.clear(null)).isInstanceOf(NullPointerException.class);
    }
}
