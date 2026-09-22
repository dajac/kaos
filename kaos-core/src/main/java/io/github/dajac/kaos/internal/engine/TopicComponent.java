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
