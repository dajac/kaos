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
        Map<String, Mode> snapshot = faults.snapshot();

        faults.clear("a");

        assertThat(snapshot).isEqualTo(Map.of("a", Mode.BLACKHOLE));
        assertThatThrownBy(() -> snapshot.put("b", Mode.RESET)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void instancesAreIndependent() {
        NetworkFaults other = new NetworkFaults();
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
