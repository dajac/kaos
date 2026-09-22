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
package io.github.dajac.kaos.internal.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import io.github.dajac.kaos.Phase;
import io.github.dajac.kaos.SimulationException;
import io.github.dajac.kaos.SimulatorSnapshot;
import io.github.dajac.kaos.TopicSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SnapshotPublisherTest {

    private static final String THREAD_NAME = "test-publisher";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final AtomicReference<SimulatorSnapshot> state = new AtomicReference<>(snapshot(Phase.IDLE));
    private final List<SimulatorSnapshot> received = new CopyOnWriteArrayList<>();
    private final List<String> listenerThreads = new CopyOnWriteArrayList<>();
    private final SnapshotPublisher publisher =
        new SnapshotPublisher(state::get, task -> new Thread(task, THREAD_NAME));

    @AfterEach
    void stop() {
        publisher.stop();
    }

    @Test
    void theInitialSnapshotHasTheRevisionZero() {
        assertThat(publisher.current()).isEqualTo(snapshot(Phase.IDLE).withRevision(0));
    }

    @Test
    void notifiesOnlyWhenTheSnapshotChanged() {
        publisher.addListener(this::record);
        publisher.start();

        publisher.flush();
        publisher.flush();
        assertThat(received).isEmpty();
        assertThat(publisher.current().revision()).isZero();

        state.set(snapshot(Phase.STARTING));
        publisher.flush();
        publisher.flush();
        state.set(snapshot(Phase.RUNNING));
        publisher.flush();

        assertThat(received).containsExactly(
            snapshot(Phase.STARTING).withRevision(1),
            snapshot(Phase.RUNNING).withRevision(2)
        );
        assertThat(publisher.current()).isEqualTo(snapshot(Phase.RUNNING).withRevision(2));
        assertThat(listenerThreads).containsOnly(THREAD_NAME);
    }

    @Test
    void ignoresTheRevisionOfTheAssembledSnapshot() {
        publisher.addListener(this::record);
        publisher.start();

        state.set(snapshot(Phase.IDLE).withRevision(42));
        publisher.flush();

        assertThat(received).isEmpty();
    }

    @Test
    void aPokeIsPublishedWithoutWaiting() {
        publisher.addListener(this::record);
        publisher.start();

        state.set(snapshot(Phase.RUNNING));
        publisher.poke();

        await().atMost(TIMEOUT)
            .untilAsserted(() -> assertThat(received).containsExactly(snapshot(Phase.RUNNING).withRevision(1)));
    }

    @Test
    void aFailingListenerDoesNotAffectTheOthersNorTheNextSnapshots() {
        publisher.addListener(snapshot -> {
            throw new IllegalStateException("listener failure");
        });
        publisher.addListener(this::record);
        publisher.start();

        state.set(snapshot(Phase.STARTING));
        publisher.flush();
        state.set(snapshot(Phase.RUNNING));
        publisher.flush();

        assertThat(received).extracting(SimulatorSnapshot::phase).containsExactly(Phase.STARTING, Phase.RUNNING);
    }

    @Test
    void aFailingAssemblerKeepsTheLastSnapshotAndThePublisherAlive() {
        var failing = new AtomicReference<>(false);
        var fragile = new SnapshotPublisher(
            () -> {
                if (failing.get()) {
                    throw new IllegalStateException("assembler failure");
                }
                return state.get();
            },
            task -> new Thread(task, THREAD_NAME)
        );
        fragile.start();
        try {
            failing.set(true);
            fragile.flush();
            assertThat(fragile.current().revision()).isZero();

            failing.set(false);
            state.set(snapshot(Phase.RUNNING));
            fragile.flush();
            assertThat(fragile.current()).isEqualTo(snapshot(Phase.RUNNING).withRevision(1));
        } finally {
            fragile.stop();
        }
    }

    @Test
    void aClosedRegistrationReceivesNothingMore() {
        var registration = publisher.addListener(this::record);
        publisher.start();
        state.set(snapshot(Phase.STARTING));
        publisher.flush();

        registration.close();
        registration.close();
        state.set(snapshot(Phase.RUNNING));
        publisher.flush();

        assertThat(received).extracting(SimulatorSnapshot::phase).containsExactly(Phase.STARTING);
    }

    @Test
    void flushWaitsForTheListeners() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        publisher.addListener(snapshot -> {
            entered.countDown();
            awaitLatch(release);
            received.add(snapshot);
        });
        publisher.start();
        state.set(snapshot(Phase.RUNNING));

        var flushed = new CountDownLatch(1);
        var flusher = new Thread(() -> {
            publisher.flush();
            flushed.countDown();
        }, "test-flusher");
        flusher.start();

        assertThat(entered.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(flushed.getCount()).isEqualTo(1);
        release.countDown();
        assertThat(flushed.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(received).hasSize(1);
    }

    @Test
    void stopPublishesTheLastStateAndEndsTheThread() {
        publisher.addListener(this::record);
        publisher.start();
        publisher.flush();
        assertThat(publisherThreads()).hasSize(1);

        state.set(snapshot(Phase.STOPPING));
        publisher.stop();
        publisher.stop();

        assertThat(received).extracting(SimulatorSnapshot::phase).containsExactly(Phase.STOPPING);
        assertThat(publisherThreads()).isEmpty();
    }

    @Test
    void withoutTheThreadNothingIsPublishedUntilTheNextStart() {
        publisher.addListener(this::record);

        state.set(snapshot(Phase.STARTING));
        publisher.poke();
        publisher.flush();
        assertThat(publisher.current().phase()).isEqualTo(Phase.IDLE);

        publisher.start();
        publisher.start();
        publisher.flush();

        assertThat(received).containsExactly(snapshot(Phase.STARTING).withRevision(1));
        assertThat(publisherThreads()).hasSize(1);
    }

    @Test
    void restartsAfterAStopAndKeepsCountingTheRevisions() {
        publisher.addListener(this::record);
        publisher.start();
        state.set(snapshot(Phase.RUNNING));
        publisher.stop();

        state.set(snapshot(Phase.IDLE));
        publisher.start();
        publisher.flush();

        assertThat(received).extracting(SimulatorSnapshot::revision).containsExactly(1L, 2L);
    }

    @Test
    void aStopStartCycleNeverRunsTwoPublishers() {
        publisher.addListener(this::record);

        for (int cycle = 0; cycle < 200; cycle++) {
            state.set(snapshot(cycle % 2 == 0 ? Phase.RUNNING : Phase.IDLE));
            publisher.start();
            publisher.poke();
            assertThat(publisherThreads()).hasSize(1);
            publisher.stop();
            assertThat(publisherThreads()).isEmpty();
        }

        // One thread at a time published, so the revisions came in order and without a gap.
        assertThat(received).extracting(SimulatorSnapshot::revision)
            .containsExactlyElementsOf(LongStream.rangeClosed(1, 200).boxed().toList());
    }

    @Test
    void theMethodsThatWaitForThePublisherThreadRefuseToRunOnIt() {
        var failures = new CopyOnWriteArrayList<Throwable>();
        publisher.addListener(snapshot -> {
            failures.add(catchThrowable(publisher::stop));
            failures.add(catchThrowable(publisher::flush));
            failures.add(catchThrowable(() -> publisher.await(s -> true, TIMEOUT)));
            assertThat(publisher.onPublisherThread()).isTrue();
            received.add(snapshot);
        });
        publisher.start();

        state.set(snapshot(Phase.RUNNING));
        publisher.flush();

        assertThat(publisher.onPublisherThread()).isFalse();
        assertThat(received).hasSize(1);
        assertThat(failures).hasSize(3)
            .allSatisfy(failure -> assertThat(failure).isInstanceOf(IllegalStateException.class));
        // The publisher still runs, and stops from another thread.
        state.set(snapshot(Phase.STOPPING));
        publisher.stop();
        assertThat(received).hasSize(2);
        assertThat(publisherThreads()).isEmpty();
    }

    @Test
    void awaitReturnsTheCurrentSnapshotWhenItMatches() {
        assertThat(publisher.await(snapshot -> snapshot.phase() == Phase.IDLE, TIMEOUT)).isEqualTo(publisher.current());
    }

    @Test
    void awaitReturnsTheFirstSnapshotThatMatches() throws Exception {
        publisher.start();
        var result = new AtomicReference<SimulatorSnapshot>();
        var waiter =
            new Thread(() -> result.set(publisher.await(s -> s.phase() == Phase.RUNNING, TIMEOUT)), "test-waiter");
        waiter.start();

        state.set(snapshot(Phase.STARTING));
        publisher.flush();
        state.set(snapshot(Phase.RUNNING));
        publisher.flush();
        waiter.join(TIMEOUT.toMillis());

        assertThat(result.get()).isNotNull();
        assertThat(result.get().phase()).isEqualTo(Phase.RUNNING);
    }

    @Test
    void awaitFailsAtTheTimeoutAndWhenTheConditionThrows() {
        assertThatThrownBy(() -> publisher.await(snapshot -> false, Duration.ofMillis(50)))
            .isInstanceOf(SimulationException.class)
            .hasMessageContaining("No snapshot matched");
        assertThatThrownBy(() -> publisher.await(
            snapshot -> {
                throw new IllegalArgumentException("bad condition");
            },
            TIMEOUT
        ))
            .isInstanceOf(SimulationException.class)
            .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    private void record(SimulatorSnapshot snapshot) {
        listenerThreads.add(Thread.currentThread().getName());
        received.add(snapshot);
    }

    private static List<Thread> publisherThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.isAlive() && thread.getName().equals(THREAD_NAME))
            .toList();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static SimulatorSnapshot snapshot(Phase phase) {
        return new SimulatorSnapshot(7, phase, false, "", List.of(new TopicSpec("t", 3)), List.of(), List.of());
    }
}
