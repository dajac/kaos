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

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The description of a load generator. A {@code null} id or list is read as empty, so that a request
 * body can leave it out.
 *
 * @param id the producer id; empty when the simulator has not assigned it yet
 * @param topics the names of the topics it writes to
 * @param ratePerPartition the records written per second to each partition
 */
public record ProducerSpec(String id, List<String> topics, double ratePerPartition) {
    public ProducerSpec {
        id = Objects.requireNonNullElse(id, "");
        topics = topics == null ? List.of() : List.copyOf(topics);
    }

    /** A producer described by an options lambda. */
    public static ProducerSpec of(Consumer<ProducerOptions> options) {
        return ProducerOptions.spec(options);
    }
}
