package io.github.dajac.kaos.internal.engine;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single factory of the simulator's threads. They are named platform threads, {@code kaos-…},
 * and daemons, so a simulator that is never closed does not keep the JVM alive. An exception that
 * ends one of them is logged.
 */
public final class Threads implements ThreadFactory {

    /** The prefix of every thread name. */
    public static final String PREFIX = "kaos-";

    private static final Logger LOG = LoggerFactory.getLogger(Threads.class);

    private final AtomicInteger workers = new AtomicInteger();

    /** Creates a thread named {@code kaos-<name>}. The caller starts it. */
    public Thread newThread(String name, Runnable task) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(task, "task");
        return Thread.ofPlatform()
            .name(PREFIX + name)
            .daemon(true)
            .uncaughtExceptionHandler(
                (thread, error) -> LOG.error("The thread {} ended on an unexpected error", thread.getName(), error))
            .unstarted(task);
    }

    /** Creates a thread named {@code kaos-worker-N}, for executors. */
    @Override
    public Thread newThread(Runnable task) {
        return newThread("worker-" + workers.getAndIncrement(), task);
    }
}
