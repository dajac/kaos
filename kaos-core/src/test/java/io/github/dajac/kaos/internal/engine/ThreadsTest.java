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
