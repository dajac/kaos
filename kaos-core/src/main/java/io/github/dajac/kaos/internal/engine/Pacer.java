package io.github.dajac.kaos.internal.engine;

/**
 * The schedule of a fixed-rate loop, as pure arithmetic on {@link System#nanoTime()} values. The
 * ticks are one period apart. A loop that falls behind does not catch up with a burst: the tick
 * it missed is due at once and the schedule restarts from there. Not thread-safe.
 */
final class Pacer {

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private final long periodNanos;
    private long nextTick;

    /**
     * @param ticksPerSecond the rate, a positive finite number
     * @param now the current time; the first tick is due at once
     */
    Pacer(double ticksPerSecond, long now) {
        if (!Double.isFinite(ticksPerSecond) || ticksPerSecond <= 0) {
            throw new IllegalArgumentException("The rate must be a finite number greater than 0, got " + ticksPerSecond);
        }
        this.periodNanos = Math.max(1, Math.round(NANOS_PER_SECOND / ticksPerSecond));
        this.nextTick = now;
    }

    long periodNanos() {
        return periodNanos;
    }

    /** How long to wait for the next tick; 0 when it is due. */
    long delayNanos(long now) {
        return Math.max(0, nextTick - now);
    }

    /** Records that the due tick was done, and schedules the next one. */
    void advance(long now) {
        // nanoTime values are only compared through their difference, which tolerates overflow.
        nextTick += periodNanos;
        if (nextTick - now < 0) {
            nextTick = now;
        }
    }

    /** Makes the next tick due at once, for a loop that resumes after a pause. */
    void reset(long now) {
        nextTick = now;
    }
}
