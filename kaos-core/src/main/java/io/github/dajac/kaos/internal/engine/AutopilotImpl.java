package io.github.dajac.kaos.internal.engine;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import io.github.dajac.kaos.Autopilot;
import io.github.dajac.kaos.AutopilotSnapshot;
import io.github.dajac.kaos.ConsumerHandle;
import io.github.dajac.kaos.SimulationException;
import io.github.dajac.kaos.SimulationStateException;
import io.github.dajac.kaos.internal.state.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The autopilot of one group. Its thread, {@code kaos-<groupId>-autopilot}, only exists while the
 * autopilot is on. After a random pause between the two intervals, it flips the subscription of one
 * random consumer of the group, through the same flag as {@code join()} and {@code leave()}.
 *
 * <p>The group opens the autopilot when it starts and closes it when it stops; the autopilot can
 * only be turned on in between. A monitor serializes these calls; the thread never takes it.
 */
public final class AutopilotImpl implements Autopilot {

    /** One run of the autopilot thread. */
    private static final class Run {
        private volatile Thread thread;
        private volatile boolean running = true;
    }

    /** The intervals of an autopilot that was never turned on: those of the prototype. */
    static final AutopilotSnapshot INITIAL = new AutopilotSnapshot(false, 10_000, 30_000);

    private static final Logger EVENTS = LoggerFactory.getLogger(EventLog.LOGGER_NAME);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final String groupId;
    private final Supplier<? extends List<? extends ConsumerHandle>> consumers;
    private final Runnable onChange;

    private volatile AutopilotSnapshot snapshot = INITIAL;

    // Guarded by the monitor of this.
    private Threads threads;
    private Run run;

    /**
     * @param groupId the group, for the thread name and the log
     * @param consumers the current consumers of the group
     * @param onChange called when the state of the autopilot changed
     */
    public AutopilotImpl(
        String groupId,
        Supplier<? extends List<? extends ConsumerHandle>> consumers,
        Runnable onChange
    ) {
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.consumers = Objects.requireNonNull(consumers, "consumers");
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    /** The state of the autopilot, as the snapshot shows it. */
    public AutopilotSnapshot snapshot() {
        return snapshot;
    }

    /** Lets the autopilot be turned on. The group calls it when it starts. */
    public synchronized void open(Threads threads) {
        this.threads = Objects.requireNonNull(threads, "threads");
    }

    /** Turns the autopilot off, until the next {@link #open}. The group calls it when it stops. */
    public synchronized void close() {
        threads = null;
        stop();
    }

    @Override
    public synchronized void start(Duration minInterval, Duration maxInterval) {
        var intervals = validate(minInterval, maxInterval);
        if (threads == null) {
            throw new SimulationStateException("The autopilot of group " + groupId + " starts once the group runs");
        }
        snapshot = intervals;
        if (run == null) {
            var started = new Run();
            var thread = threads.newThread(groupId + "-autopilot", () -> loop(started));
            started.thread = thread;
            thread.start();
            run = started;
        } else {
            // The thread draws its next pause from the new intervals.
            LockSupport.unpark(run.thread);
        }
        onChange.run();
    }

    @Override
    public synchronized void stop() {
        var current = snapshot;
        snapshot = new AutopilotSnapshot(false, current.minIntervalMs(), current.maxIntervalMs());
        var stopped = run;
        run = null;
        if (stopped != null) {
            stopped.running = false;
            LockSupport.unpark(stopped.thread);
            join(stopped.thread);
        }
        if (current.enabled()) {
            onChange.run();
        }
    }

    @Override
    public boolean enabled() {
        return snapshot.enabled();
    }

    /** The thread only parks and flips flags, so it ends at once. */
    private void join(Thread thread) {
        if (thread == Thread.currentThread()) {
            return;
        }
        try {
            if (!thread.join(STOP_TIMEOUT)) {
                throw new SimulationException(
                    "The thread " + thread.getName() + " did not stop within " + STOP_TIMEOUT
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Checks the intervals and returns them as the snapshot of an autopilot that is on.
     *
     * @throws IllegalArgumentException if an interval is shorter than 1 ms or longer than an
     *     {@code int} of milliseconds, or if {@code min > max}
     */
    static AutopilotSnapshot validate(Duration minInterval, Duration maxInterval) {
        Objects.requireNonNull(minInterval, "minInterval");
        Objects.requireNonNull(maxInterval, "maxInterval");
        if (minInterval.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("The minimum interval must be at least 1 ms, got " + minInterval);
        }
        if (minInterval.compareTo(maxInterval) > 0) {
            throw new IllegalArgumentException(
                "The minimum interval must not exceed the maximum interval, got " + minInterval + " and " + maxInterval
            );
        }
        if (maxInterval.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("The maximum interval is too long: " + maxInterval);
        }
        return new AutopilotSnapshot(true, (int) minInterval.toMillis(), (int) maxInterval.toMillis());
    }

    private void loop(Run self) {
        while (self.running) {
            var intervals = snapshot;
            var random = ThreadLocalRandom.current();
            var pauseMs = random.nextLong(intervals.minIntervalMs(), intervals.maxIntervalMs() + 1L);
            var deadline = System.nanoTime() + Duration.ofMillis(pauseMs).toNanos();
            var remaining = deadline - System.nanoTime();
            // The wait ends early when the autopilot stops or gets new intervals.
            while (self.running && snapshot.equals(intervals) && remaining > 0) {
                LockSupport.parkNanos(this, remaining);
                remaining = deadline - System.nanoTime();
            }
            if (self.running && snapshot.equals(intervals)) {
                flip();
            }
        }
    }

    /** Flips the subscription of one random consumer. A group without consumers is left alone. */
    void flip() {
        var candidates = consumers.get();
        if (candidates.isEmpty()) {
            return;
        }
        var victim = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        if (victim.subscribed()) {
            EVENTS.info("[{}] autopilot asks {} to leave", groupId, victim.id());
            victim.leave();
        } else {
            EVENTS.info("[{}] autopilot asks {} to join", groupId, victim.id());
            victim.join();
        }
    }
}
