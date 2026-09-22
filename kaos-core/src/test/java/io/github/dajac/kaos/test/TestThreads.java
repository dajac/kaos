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
