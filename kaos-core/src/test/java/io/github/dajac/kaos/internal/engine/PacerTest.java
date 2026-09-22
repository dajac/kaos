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
package io.github.dajac.kaos.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;

import io.github.dajac.kaos.ConfigMetadata;
import org.junit.jupiter.api.Test;

class PacerTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    @Test
    void theDefaultRateIsOneTickEvery500Ms() {
        var pacer = new Pacer(ConfigMetadata.RATE_PER_PARTITION.doubleDefault(), 0);

        assertThat(pacer.periodNanos()).isEqualTo(500 * MS);
    }

    @Test
    void theFirstTickIsDueAtOnceAndTheNextOnesAreOnePeriodApart() {
        var start = 1_000 * MS;
        var pacer = new Pacer(10, start);

        assertThat(pacer.delayNanos(start)).isZero();
        pacer.advance(start + MS);
        // The round took 1 ms; the schedule does not drift with it.
        assertThat(pacer.delayNanos(start + MS)).isEqualTo(99 * MS);
        assertThat(pacer.delayNanos(start + 100 * MS)).isZero();
        pacer.advance(start + 100 * MS);
        assertThat(pacer.delayNanos(start + 100 * MS)).isEqualTo(100 * MS);
    }

    @Test
    void aLateLoopDoesNotCatchUpWithABurst() {
        var pacer = new Pacer(10, 0);
        pacer.advance(0);

        // The loop comes back after 5 periods: one tick is due, not five.
        var late = 500 * MS;
        assertThat(pacer.delayNanos(late)).isZero();
        pacer.advance(late);
        assertThat(pacer.delayNanos(late)).isZero();
        pacer.advance(late);
        assertThat(pacer.delayNanos(late)).isEqualTo(100 * MS);
    }

    @Test
    void resetMakesTheNextTickDueAtOnce() {
        var pacer = new Pacer(10, 0);
        pacer.advance(0);
        assertThat(pacer.delayNanos(10 * MS)).isEqualTo(90 * MS);

        pacer.reset(10 * MS);

        assertThat(pacer.delayNanos(10 * MS)).isZero();
    }

    @Test
    void toleratesTheOverflowOfNanoTime() {
        var start = Long.MAX_VALUE - 10 * MS;
        var pacer = new Pacer(10, start);

        pacer.advance(start);

        assertThat(pacer.delayNanos(start + 40 * MS)).isEqualTo(60 * MS);
    }

    @Test
    void aVeryHighRateHasAPeriodOfAtLeastOneNano() {
        assertThat(new Pacer(1e12, 0).periodNanos()).isEqualTo(1);
    }

    @Test
    void refusesARateThatIsNotPositiveAndFinite() {
        assertThatThrownBy(() -> new Pacer(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pacer(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pacer(Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pacer(Double.POSITIVE_INFINITY, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
