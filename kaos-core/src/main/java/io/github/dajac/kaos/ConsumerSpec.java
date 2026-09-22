package io.github.dajac.kaos;

import java.util.Objects;
import java.util.function.Consumer;

import io.github.dajac.kaos.internal.config.EffectiveConfigResolver;

/**
 * The description of one consumer of a group.
 *
 * <p>{@code effective} is derived: it is {@code overrides} applied on top of the
 * {@code consumerDefaults} of the group. A {@link GroupSpec} always recomputes it for its consumers.
 * A spec that is not part of a group yet resolves against {@link ConsumerSettings#defaults()} when
 * {@code effective} is {@code null}, and keeps a non-null {@code effective} as given. That value is
 * informative only: it is not trusted. Validating the consumer or adding it to a group resolves it
 * again against the defaults of that group, and never reads the {@code effective} of its argument.
 *
 * <p>A {@code null} id is read as empty and {@code null} overrides as
 * {@link ConsumerOverrides#none()}, so that a request body can leave them out.
 *
 * @param id the consumer id, unique within the group; empty when the group has not assigned it yet
 * @param overrides the settings the consumer sets itself
 * @param effective the resolved settings
 */
public record ConsumerSpec(String id, ConsumerOverrides overrides, ConsumerSettings effective) {
    public ConsumerSpec {
        id = Objects.requireNonNullElse(id, "");
        overrides = Objects.requireNonNullElseGet(overrides, ConsumerOverrides::none);
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
