package io.github.dajac.kaos.internal.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import io.github.dajac.kaos.SimulationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A stack of undo actions. A step that succeeded registers how to undo it; closing the stack runs
 * the undo actions in reverse order. A failed start, a removal and a close all unwind this way.
 *
 * <p>Stacks nest: the simulator has the top-level stack and each component has a {@link #child}
 * of it. Closing a child undoes that component only; closing the parent undoes the children at
 * their position in the stack.
 *
 * <p>Every undo action runs, whatever the others do. Their failures are collected, across the
 * nested stacks, and attached as suppressed exceptions to a single {@link SimulationException}.
 * The instances are thread-safe, and the undo actions run outside of the monitor.
 */
public final class Resources {

    /** An undo action. */
    @FunctionalInterface
    public interface Undo {
        void run() throws Exception;
    }

    /** A step that registers its undo actions on the stack it is given. */
    @FunctionalInterface
    public interface Step {
        void run(Resources resources);
    }

    private record Entry(String description, Undo undo, Resources child) {}

    private static final Logger LOG = LoggerFactory.getLogger(Resources.class);

    private final String name;
    private final Resources parent;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private boolean closed;

    /** Creates a top-level stack. The name appears in the logs and in the failure message. */
    public Resources(String name) {
        this(name, null);
    }

    private Resources(String name, Resources parent) {
        this.name = Objects.requireNonNull(name, "name");
        this.parent = parent;
    }

    /**
     * Registers the undo action of a step that succeeded.
     *
     * @throws IllegalStateException if the stack is closed
     */
    public void register(String description, Undo undo) {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(undo, "undo");
        push(new Entry(description, undo, null));
    }

    /**
     * Creates a nested stack, which is closed with this one, at its position in the stack, unless
     * it is closed before.
     *
     * @throws IllegalStateException if the stack is closed
     */
    public Resources child(String name) {
        var child = new Resources(name, this);
        push(new Entry(name, null, child));
        return child;
    }

    /**
     * Runs a step on a new nested stack and returns that stack. When the step fails, the stack is
     * closed at once, which undoes what the step did before it failed, and the failure is thrown
     * with the failures of the undo actions attached; the parent is then unchanged.
     *
     * @throws IllegalStateException if the stack is closed
     */
    public Resources start(String name, Step step) {
        var child = child(name);
        try {
            step.run(child);
        } catch (Throwable e) {
            child.close(e);
            throw e;
        }
        return child;
    }

    private synchronized void push(Entry entry) {
        if (closed) {
            throw new IllegalStateException("The resources of " + name + " are closed");
        }
        entries.push(entry);
    }

    /**
     * Runs the undo actions in reverse order. The call is idempotent: the actions run once.
     *
     * @throws SimulationException if undo actions failed, with their failures as suppressed
     *     exceptions
     */
    public void close() {
        var failures = unwind();
        if (!failures.isEmpty()) {
            var failure = new SimulationException(failures.size() + " undo action(s) of " + name + " failed");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    /**
     * Runs the undo actions in reverse order after a step failed, and attaches their failures to
     * the failure of that step as suppressed exceptions.
     */
    public void close(Throwable primary) {
        Objects.requireNonNull(primary, "primary");
        unwind().forEach(primary::addSuppressed);
    }

    private List<Throwable> unwind() {
        var failures = new ArrayList<Throwable>();
        // A pending interrupt would make every blocking undo action fail at once. It is put back
        // when all of them have run.
        var interrupted = Thread.interrupted();
        try {
            interrupted |= unwindInto(failures);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (parent != null) {
            parent.forget(this);
        }
        return failures;
    }

    /** Returns whether an undo action was interrupted. */
    private boolean unwindInto(List<Throwable> failures) {
        List<Entry> pending;
        synchronized (this) {
            closed = true;
            pending = List.copyOf(entries);
            entries.clear();
        }
        var interrupted = false;
        for (var entry : pending) {
            if (entry.child() != null) {
                interrupted |= entry.child().unwindInto(failures);
                continue;
            }
            try {
                entry.undo().run();
            } catch (InterruptedException e) {
                interrupted = true;
                LOG.warn("Interrupted while undoing '{}' of {}", entry.description(), name);
                failures.add(e);
            } catch (Exception e) {
                LOG.warn("Could not undo '{}' of {}", entry.description(), name, e);
                failures.add(e);
            }
            // The flag may be set without an exception; the next undo action must not see it.
            interrupted |= Thread.interrupted();
        }
        return interrupted;
    }

    /** Drops a child that was closed on its own. */
    private synchronized void forget(Resources child) {
        entries.removeIf(entry -> entry.child() == child);
    }
}
