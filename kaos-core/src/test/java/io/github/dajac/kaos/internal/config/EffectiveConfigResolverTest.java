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
package io.github.dajac.kaos.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import io.github.dajac.kaos.ConsumerOverrides;
import io.github.dajac.kaos.ConsumerSettings;
import io.github.dajac.kaos.ConsumerSpec;
import io.github.dajac.kaos.Protocol;
import org.junit.jupiter.api.Test;

class EffectiveConfigResolverTest {
    private static final ConsumerSettings DEFAULTS =
        new ConsumerSettings(Protocol.CONSUMER, "uniform", false, "rack-0", 10, 20, 30_000);

    @Test
    void noOverrideInheritsEverything() {
        assertThat(EffectiveConfigResolver.resolve(DEFAULTS, ConsumerOverrides.none())).isEqualTo(DEFAULTS);
    }

    @Test
    void everyOverrideWins() {
        var overrides = new ConsumerOverrides(
            Optional.of(Protocol.CLASSIC),
            Optional.of("sticky"),
            Optional.of(true),
            Optional.of("rack-1"),
            OptionalInt.of(11),
            OptionalInt.of(21),
            OptionalInt.of(31_000)
        );

        assertThat(EffectiveConfigResolver.resolve(DEFAULTS, overrides))
            .isEqualTo(new ConsumerSettings(Protocol.CLASSIC, "sticky", true, "rack-1", 11, 21, 31_000));
    }

    @Test
    void eachValueIsResolvedOnItsOwn() {
        var overrides = new ConsumerOverrides(
            Optional.empty(),
            Optional.empty(),
            Optional.of(true),
            Optional.empty(),
            OptionalInt.empty(),
            OptionalInt.of(0),
            OptionalInt.empty()
        );

        assertThat(EffectiveConfigResolver.resolve(DEFAULTS, overrides))
            .isEqualTo(new ConsumerSettings(Protocol.CONSUMER, "uniform", true, "rack-0", 10, 0, 30_000));
    }

    @Test
    void anOverrideEqualToTheFalsyDefaultStillCounts() {
        var staticDefaults = new ConsumerSettings(Protocol.CONSUMER, "uniform", true, "rack-0", 10, 20, 30_000);
        var overrides = new ConsumerOverrides(
            Optional.empty(), Optional.of(""), Optional.of(false), Optional.of(""),
            OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty()
        );

        var effective = EffectiveConfigResolver.resolve(staticDefaults, overrides);

        assertThat(effective.staticMember()).isFalse();
        assertThat(effective.assignor()).isEmpty();
        assertThat(effective.rackId()).isEmpty();
    }

    @Test
    void resolvingAConsumerKeepsItsIdAndOverrides() {
        var consumer = new ConsumerSpec("consumer-3", ConsumerOverrides.none(), ConsumerSettings.defaults());

        var resolved = EffectiveConfigResolver.resolve(DEFAULTS, consumer);

        assertThat(resolved).isEqualTo(new ConsumerSpec("consumer-3", ConsumerOverrides.none(), DEFAULTS));
    }

    @Test
    void resolvingAListReturnsAnImmutableCopy() {
        var resolved =
            EffectiveConfigResolver.resolve(DEFAULTS, List.of(ConsumerSpec.inheriting(), ConsumerSpec.inheriting()));

        assertThat(resolved).extracting(ConsumerSpec::effective).containsExactly(DEFAULTS, DEFAULTS);
        assertThatThrownBy(() -> resolved.add(ConsumerSpec.inheriting()))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
