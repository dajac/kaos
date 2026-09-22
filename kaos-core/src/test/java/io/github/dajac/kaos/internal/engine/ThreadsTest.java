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

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ThreadsTest {

    private final Threads threads = new Threads();

    @Test
    void createsNamedDaemonPlatformThreadsThatAreNotStarted() {
        var thread = threads.newThread("publisher", () -> {});

        assertThat(thread.getName()).isEqualTo("kaos-publisher");
        assertThat(thread.isDaemon()).isTrue();
        assertThat(thread.isVirtual()).isFalse();
        assertThat(thread.getState()).isEqualTo(Thread.State.NEW);
    }

    @Test
    void numbersTheThreadsThatHaveNoName() {
        assertThat(threads.newThread(() -> {}).getName()).isEqualTo("kaos-worker-0");
        assertThat(threads.newThread(() -> {}).getName()).isEqualTo("kaos-worker-1");
        assertThat(new Threads().newThread(() -> {}).getName()).isEqualTo("kaos-worker-0");
    }

    @Test
    void anUncaughtExceptionGoesToTheHandlerOfTheFactory() throws Exception {
        var thread = threads.newThread("failing", () -> {
            throw new IllegalStateException("unexpected");
        });

        // Without a handler of its own, a thread reports its thread group.
        assertThat(thread.getUncaughtExceptionHandler()).isNotSameAs(thread.getThreadGroup());
        thread.start();
        assertThat(thread.join(Duration.ofSeconds(10))).isTrue();
    }
}
