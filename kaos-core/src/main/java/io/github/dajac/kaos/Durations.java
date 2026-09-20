package io.github.dajac.kaos;

import java.time.Duration;

/** Converts the durations of the options lambdas to the millisecond values of the specs. */
final class Durations {
    private Durations() {}

    /**
     * The duration in milliseconds. A duration outside the {@code int} range saturates, so that the
     * validation reports it as out of range instead of this conversion failing.
     */
    static int toMillis(Duration duration) {
        long millis;
        try {
            millis = duration.toMillis();
        } catch (ArithmeticException e) {
            millis = duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return Math.clamp(millis, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }
}
