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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.dajac.kaos.AutopilotSnapshot;
import io.github.dajac.kaos.ConsumerHandle;
import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.Protocol;
import io.github.dajac.kaos.SimulationStateException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AutopilotImplTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration QUIET = Duration.ofMillis(300);

    /** A consumer that only records its subscription flag. */
    private static final class Flag implements ConsumerHandle {
        private final AtomicBoolean subscribed = new AtomicBoolean(true);
        private final AtomicInteger flips = new AtomicInteger();

        @Override
        public String id() {
            return "consumer-0";
        }

        @Override
        public String clientId() {
            return "g-consumer-0";
        }

        @Override
        public Protocol protocol() {
            return Protocol.CONSUMER;
        }

        @Override
        public boolean subscribed() {
            return subscribed.get();
        }

        @Override
        public void inject(Fault fault) {}

        @Override
        public void clear(Fault fault) {}

        @Override
        public void clearFaults() {}

        @Override
        public Set<Fault> faults() {
            return Set.of();
        }

        @Override
        public void join() {
            subscribed.set(true);
            flips.incrementAndGet();
        }

        @Override
        public void leave() {
            subscribed.set(false);
            flips.incrementAndGet();
        }

        @Override
        public void switchProtocol(Protocol protocol) {}

        @Override
        public void remove() {}
    }

    private final List<Flag> consumers = new CopyOnWriteArrayList<>();
    private final AtomicInteger changes = new AtomicInteger();
    private final Threads threads = new Threads();
    private final AutopilotImpl autopilot = new AutopilotImpl("g", () -> consumers, changes::incrementAndGet);

    @AfterEach
    void close() {
        autopilot.close();
    }

    @Test
    void startsOffWithThePrototypeIntervals() {
        assertThat(autopilot.enabled()).isFalse();
        assertThat(autopilot.snapshot()).isEqualTo(new AutopilotSnapshot(false, 10_000, 30_000));
    }

    @Test
    void validatesTheIntervals() {
        assertThatThrownBy(() -> AutopilotImpl.validate(Duration.ofSeconds(2), Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not exceed");
        assertThatThrownBy(() -> AutopilotImpl.validate(Duration.ZERO, Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 1 ms");
        assertThatThrownBy(() -> AutopilotImpl.validate(Duration.ofSeconds(1), Duration.ofDays(30)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("too long");
        assertThat(AutopilotImpl.validate(Duration.ofSeconds(1), Duration.ofSeconds(1)))
            .isEqualTo(new AutopilotSnapshot(true, 1000, 1000));
    }

    @Test
    void refusesToStartWhileTheGroupDoesNotRun() {
        assertThatThrownBy(() -> autopilot.start(Duration.ofSeconds(1), Duration.ofSeconds(2)))
            .isInstanceOf(SimulationStateException.class);
        // The intervals are checked first, whatever the state.
        assertThatThrownBy(() -> autopilot.start(Duration.ofSeconds(2), Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(autopilot.enabled()).isFalse();
    }

    @Test
    void flipsOneConsumerAtRandomTimesWhileOn() {
        var consumer = new Flag();
        consumers.add(consumer);
        autopilot.open(threads);

        autopilot.start(Duration.ofMillis(10), Duration.ofMillis(30));

        assertThat(autopilot.snapshot()).isEqualTo(new AutopilotSnapshot(true, 10, 30));
        assertThat(threadNames()).containsExactly("kaos-g-autopilot");
        await().atMost(TIMEOUT).until(() -> consumer.flips.get() >= 3);

        autopilot.stop();

        assertThat(autopilot.snapshot()).isEqualTo(new AutopilotSnapshot(false, 10, 30));
        await().atMost(TIMEOUT).until(() -> threadNames().isEmpty());
        var flips = consumer.flips.get();
        await().during(QUIET).atMost(TIMEOUT).until(() -> consumer.flips.get() == flips);
        assertThat(changes.get()).isEqualTo(2);
    }

    @Test
    void doesNothingWithoutConsumers() {
        autopilot.open(threads);

        autopilot.start(Duration.ofMillis(1), Duration.ofMillis(2));

        await().during(QUIET).atMost(TIMEOUT).until(() -> autopilot.enabled());
        autopilot.flip();
    }

    @Test
    void newIntervalsReplaceTheCurrentPauseAndAStopIsIdempotent() {
        consumers.add(new Flag());
        autopilot.open(threads);
        autopilot.start(Duration.ofHours(1), Duration.ofHours(2));

        autopilot.start(Duration.ofMillis(1), Duration.ofMillis(5));

        assertThat(threadNames()).hasSize(1);
        await().atMost(TIMEOUT).until(() -> consumers.get(0).flips.get() > 0);

        autopilot.stop();
        autopilot.stop();

        assertThat(changes.get()).isEqualTo(3);
    }

    @Test
    void closingTheGroupTurnsItOff() {
        consumers.add(new Flag());
        autopilot.open(threads);
        autopilot.start(Duration.ofHours(1), Duration.ofHours(2));

        autopilot.close();

        assertThat(autopilot.enabled()).isFalse();
        await().atMost(TIMEOUT).until(() -> threadNames().isEmpty());
        assertThatThrownBy(() -> autopilot.start(Duration.ofSeconds(1), Duration.ofSeconds(2)))
            .isInstanceOf(SimulationStateException.class);
    }

    private static List<String> threadNames() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isAlive)
            .map(Thread::getName)
            .filter(name -> name.equals("kaos-g-autopilot"))
            .toList();
    }
}
