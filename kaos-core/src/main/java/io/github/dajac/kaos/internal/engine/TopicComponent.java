package io.github.dajac.kaos.internal.engine;

import io.github.dajac.kaos.Topic;
import io.github.dajac.kaos.TopicSpec;

import java.util.Objects;

/** A topic of a simulator. */
record TopicComponent(TopicSpec spec) implements Topic {
    TopicComponent {
        Objects.requireNonNull(spec, "spec");
    }

    @Override
    public String name() {
        return spec.name();
    }

    @Override
    public int partitions() {
        return spec.partitions();
    }
}
