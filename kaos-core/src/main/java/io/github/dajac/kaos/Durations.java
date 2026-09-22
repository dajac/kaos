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
