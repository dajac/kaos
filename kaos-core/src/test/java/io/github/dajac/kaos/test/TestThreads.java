package io.github.dajac.kaos.test;

import java.util.List;
import java.util.Set;

/** Lists the threads a test started, for the checks that code under test leaks no thread. */
public final class TestThreads {

    /**
     * Name prefixes of the threads that belong to no code under test: Awaitility, and the workers
     * of the common pool of the JVM, which the JVM starts on first use and keeps idle for a minute.
     */
    private static final List<String> IGNORED = List.of("awaitility", "ForkJoinPool.commonPool-worker-");

    private TestThreads() {}

    /**
     * Returns the names, sorted, of the live threads that are not in {@code before}, except
     * Awaitility's and the workers of the common pool.
     *
     * @param before the threads of the JVM when the test started, from
     *     {@code Thread.getAllStackTraces().keySet()}
     */
    public static List<String> startedSince(Set<Thread> before) {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isAlive)
            .filter(thread -> !before.contains(thread))
            .map(Thread::getName)
            .filter(name -> IGNORED.stream().noneMatch(name::startsWith))
            .sorted()
            .toList();
    }
}
