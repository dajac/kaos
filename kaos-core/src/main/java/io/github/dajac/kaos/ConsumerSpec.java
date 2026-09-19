package io.github.dajac.kaos;

import io.github.dajac.kaos.internal.config.EffectiveConfigResolver;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The description of one consumer of a group.
 *
 * <p>{@code effective} is derived: it is {@code overrides} applied on top of the
 * {@code consumerDefaults} of the group. A {@link GroupSpec} always recomputes it for its consumers.
 * A spec that is not part of a group yet resolves against {@link ConsumerSettings#defaults()} when
 * {@code effective} is {@code null}.
 *
 * @param id the consumer id, unique within the group; empty when the group has not assigned it yet
 * @param overrides the settings the consumer sets itself
 * @param effective the resolved settings
 */
public record ConsumerSpec(String id, ConsumerOverrides overrides, ConsumerSettings effective) {
    public ConsumerSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(overrides, "overrides");
        if (effective == null) {
            effective = EffectiveConfigResolver.resolve(ConsumerSettings.defaults(), overrides);
        }
    }

    /** A consumer with the given overrides and no id yet. */
    public static ConsumerSpec of(ConsumerOverrides overrides) {
        return new ConsumerSpec("", overrides, null);
    }

    /** A consumer described by an options lambda, with no id yet. */
    public static ConsumerSpec of(Consumer<ConsumerOptions> options) {
        return of(ConsumerOptions.overrides(options));
    }

    /** A consumer that inherits every setting, with no id yet. */
    public static ConsumerSpec inheriting() {
        return of(ConsumerOverrides.none());
    }
}
