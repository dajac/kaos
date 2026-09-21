package io.github.dajac.kaos.internal.state;

import io.github.dajac.kaos.Registration;
import io.github.dajac.kaos.SimulationException;
import io.github.dajac.kaos.SimulatorSnapshot;
import io.github.dajac.kaos.SnapshotListener;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes the snapshots of a simulator from one thread.
 *
 * <p>Whoever changes something the snapshot shows calls {@link #poke()}. The publisher thread
 * then assembles a snapshot and compares it with the last one, ignoring the revision. When it
 * differs, the thread bumps the revision, stores the snapshot and notifies the listeners. Several
 * pokes may end up in one snapshot; {@link #flush()} is for the changes that must not be
 * coalesced with a later one, such as a phase change.
 *
 * <p>The thread runs between {@link #start()} and {@link #stop()}, which the owner serializes.
 * The other methods can be called from any thread at any time, and take no lock. Without the
 * thread, a poke has no effect until the next start.
 *
 * <p>{@link #stop()}, {@link #flush()} and {@link #await} wait for the publisher thread, so they
 * refuse to run on it, that is in a listener. The owner checks {@link #onPublisherThread()} before
 * it takes a lock that a caller of these methods may hold.
 */
public final class SnapshotPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotPublisher.class);

    private final Supplier<SimulatorSnapshot> assembler;
    private final ThreadFactory threads;
    private final AtomicReference<SimulatorSnapshot> current;
    private final List<SnapshotListener> listeners = new CopyOnWriteArrayList<>();
    private final Queue<Thread> waiters = new ConcurrentLinkedQueue<>();

    /** The number of publications asked for, and the number the thread has caught up with. */
    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong processed = new AtomicLong();

    private volatile Thread thread;
    private volatile boolean stopping;

    /**
     * @param assembler builds a snapshot of the current state; its revision is ignored. It is
     *     called on the publisher thread, and once by this constructor for the initial snapshot,
     *     which has the revision 0.
     * @param threads creates the publisher thread
     */
    public SnapshotPublisher(Supplier<SimulatorSnapshot> assembler, ThreadFactory threads) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.current = new AtomicReference<>(assembler.get().withRevision(0));
    }

    /** The latest snapshot. */
    public SimulatorSnapshot current() {
        return current.get();
    }

    /** Adds a listener. It is called for the snapshots published from now on. */
    public Registration addListener(SnapshotListener listener) {
        Objects.requireNonNull(listener, "listener");
        // The wrapper gives each registration its own identity: registering a listener twice and
        // closing one registration keeps the other.
        SnapshotListener registered = listener::onSnapshot;
        listeners.add(registered);
        return () -> listeners.remove(registered);
    }

    /** Whether the caller runs on the publisher thread, which means in a listener. */
    public boolean onPublisherThread() {
        return Thread.currentThread() == thread;
    }

    /** Starts the publisher thread, which first publishes the current state. Idempotent. */
    public void start() {
        if (thread != null) {
            return;
        }
        stopping = false;
        requested.incrementAndGet();
        var started = threads.newThread(this::run);
        thread = started;
        started.start();
    }

    /**
     * Publishes the current state one last time and stops the publisher thread. Idempotent. The
     * thread has ended when the call returns, so a following start never runs next to it.
     *
     * @throws IllegalStateException on the publisher thread
     */
    public void stop() {
        refusePublisherThread("stop");
        var stopped = thread;
        if (stopped == null) {
            return;
        }
        stopping = true;
        LockSupport.unpark(stopped);
        var interrupted = false;
        while (stopped.isAlive()) {
            try {
                stopped.join();
            } catch (InterruptedException e) {
                // The last snapshot is part of the contract of stop(), so the wait goes on.
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    /** Asks for a publication and returns immediately. */
    public void poke() {
        requested.incrementAndGet();
        LockSupport.unpark(thread);
    }

    /**
     * Asks for a publication and waits until the publisher thread has done it, listeners
     * included. It does not wait when the thread does not run or when the caller is interrupted.
     *
     * @throws IllegalStateException on the publisher thread
     */
    public void flush() {
        refusePublisherThread("flush");
        var publisher = thread;
        var caller = Thread.currentThread();
        if (publisher == null) {
            poke();
            return;
        }
        waiters.add(caller);
        try {
            var target = requested.incrementAndGet();
            LockSupport.unpark(publisher);
            while (processed.get() < target && publisher.isAlive() && !caller.isInterrupted()) {
                LockSupport.park(this);
            }
        } finally {
            waiters.remove(caller);
        }
    }

    /**
     * Waits until a snapshot matches, starting with the current one, and returns it.
     *
     * @throws SimulationException if no snapshot matches within the timeout, if the condition
     *     throws or if the caller is interrupted
     * @throws IllegalStateException on the publisher thread
     */
    public SimulatorSnapshot await(Predicate<SimulatorSnapshot> condition, Duration timeout) {
        refusePublisherThread("await");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(timeout, "timeout");
        var match = new CompletableFuture<SimulatorSnapshot>();
        SnapshotListener listener = snapshot -> {
            try {
                if (condition.test(snapshot)) {
                    match.complete(snapshot);
                }
            } catch (RuntimeException e) {
                match.completeExceptionally(e);
            }
        };
        // The listener is registered before the current snapshot is tested, so a snapshot
        // published in between is not missed.
        var registration = addListener(listener);
        try {
            listener.onSnapshot(current());
            return match.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new SimulationException(
                "No snapshot matched the condition within " + timeout + "; the last one is " + current()
            );
        } catch (ExecutionException e) {
            throw new SimulationException("The snapshot condition failed: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SimulationException("Interrupted while waiting for a snapshot", e);
        } finally {
            registration.close();
        }
    }

    private void refusePublisherThread(String method) {
        if (onPublisherThread()) {
            throw new IllegalStateException(method + "() waits for the publisher thread and was called on it");
        }
    }

    private void run() {
        while (true) {
            // Read before publishing: the publication that follows a stop request is the last.
            var last = stopping;
            var target = requested.get();
            if (target > processed.get() || last) {
                publish();
                processed.set(target);
                waiters.forEach(LockSupport::unpark);
            }
            if (last) {
                return;
            }
            if (requested.get() == processed.get() && !stopping) {
                LockSupport.park(this);
            }
        }
    }

    private void publish() {
        SimulatorSnapshot published;
        try {
            var last = current.get();
            var assembled = assembler.get().withRevision(last.revision());
            if (assembled.equals(last)) {
                return;
            }
            published = assembled.withRevision(last.revision() + 1);
            current.set(published);
        } catch (RuntimeException e) {
            LOG.error("Could not assemble the snapshot", e);
            return;
        }
        for (var listener : listeners) {
            try {
                listener.onSnapshot(published);
            } catch (RuntimeException e) {
                LOG.warn("A snapshot listener failed on revision {}", published.revision(), e);
            }
        }
    }
}
