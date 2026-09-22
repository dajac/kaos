package io.github.dajac.kaos.internal.engine;

import java.util.Objects;
import java.util.function.Consumer;

import io.github.dajac.kaos.SimulationStateException;
import io.github.dajac.kaos.Topic;
import io.github.dajac.kaos.TopicSpec;

/** A topic of a simulator. The simulator removes it, under its structure lock. */
final class TopicComponent implements Topic {

    private final TopicSpec spec;
    private final Consumer<TopicComponent> remover;

    private volatile boolean removed;

    /**
     * @param spec the description of the topic
     * @param remover removes the topic from the simulator
     */
    TopicComponent(TopicSpec spec, Consumer<TopicComponent> remover) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.remover = Objects.requireNonNull(remover, "remover");
    }

    TopicSpec spec() {
        return spec;
    }

    @Override
    public String name() {
        return spec.name();
    }

    @Override
    public int partitions() {
        return spec.partitions();
    }

    @Override
    public void remove() {
        remover.accept(this);
    }

    /** Called by the simulator once the topic is removed. */
    void markRemoved() {
        removed = true;
    }

    /** @throws SimulationStateException if the topic is removed */
    void checkNotRemoved() {
        if (removed) {
            throw new SimulationStateException("The topic " + spec.name() + " is removed");
        }
    }
}
