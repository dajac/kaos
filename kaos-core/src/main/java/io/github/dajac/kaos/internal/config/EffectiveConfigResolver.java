package io.github.dajac.kaos.internal.config;

import java.util.List;

import io.github.dajac.kaos.ConsumerOverrides;
import io.github.dajac.kaos.ConsumerSettings;
import io.github.dajac.kaos.ConsumerSpec;

/**
 * The one place where the effective settings of a consumer are computed: each override that is set
 * wins, and every other value is inherited from the defaults.
 */
public final class EffectiveConfigResolver {
    private EffectiveConfigResolver() {}

    /** Applies the overrides on top of the defaults. */
    public static ConsumerSettings resolve(ConsumerSettings defaults, ConsumerOverrides overrides) {
        return new ConsumerSettings(
            overrides.protocol().orElse(defaults.protocol()),
            overrides.assignor().orElse(defaults.assignor()),
            overrides.staticMember().orElse(defaults.staticMember()),
            overrides.rackId().orElse(defaults.rackId()),
            overrides.pollTimeoutMs().orElse(defaults.pollTimeoutMs()),
            overrides.pollDelayMs().orElse(defaults.pollDelayMs()),
            overrides.maxPollIntervalMs().orElse(defaults.maxPollIntervalMs())
        );
    }

    /** Returns the consumer with its effective settings recomputed from the defaults. */
    public static ConsumerSpec resolve(ConsumerSettings defaults, ConsumerSpec consumer) {
        return new ConsumerSpec(consumer.id(), consumer.overrides(), resolve(defaults, consumer.overrides()));
    }

    /** Returns an immutable copy of the consumers, each resolved against the defaults. */
    public static List<ConsumerSpec> resolve(ConsumerSettings defaults, List<ConsumerSpec> consumers) {
        return consumers.stream().map(consumer -> resolve(defaults, consumer)).toList();
    }
}
