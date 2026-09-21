package io.github.dajac.kaos.internal.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.dajac.kaos.Phase;
import io.github.dajac.kaos.SimulationException;
import io.github.dajac.kaos.SimulatorSnapshot;
import io.github.dajac.kaos.TopicSpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class SnapshotPublisherTest {

    private static final String THREAD_NAME = "test-publisher";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final AtomicReference<SimulatorSnapshot> state = new AtomicReference<>(snapshot(Phase.IDLE));
    private final List<SimulatorSnapshot> received = new CopyOnWriteArrayList<>();
    private final List<String> listenerThreads = new CopyOnWriteArrayList<>();
    private final SnapshotPublisher publisher = new SnapshotPublisher(state::get, task -> new Thread(task, THREAD_NAME));

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

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(received).containsExactly(snapshot(Phase.RUNNING).withRevision(1)));
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
    void awaitReturnsTheCurrentSnapshotWhenItMatches() {
        assertThat(publisher.await(snapshot -> snapshot.phase() == Phase.IDLE, TIMEOUT)).isEqualTo(publisher.current());
    }

    @Test
    void awaitReturnsTheFirstSnapshotThatMatches() throws Exception {
        publisher.start();
        var result = new AtomicReference<SimulatorSnapshot>();
        var waiter = new Thread(() -> result.set(publisher.await(s -> s.phase() == Phase.RUNNING, TIMEOUT)), "test-waiter");
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
