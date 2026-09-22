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

/**
 * A load generator of a simulator. It writes records to every partition of its topics at a fixed
 * rate. Every method is thread-safe.
 */
public interface ProducerHandle {
    /** The producer id. */
    String id();

    /** The names of the topics it writes to. */
    List<String> topics();

    /** The records written per second to each partition. */
    double ratePerPartition();

    /** Whether the producer runs, is paused or is stopped. */
    ProducerState state();

    /**
     * Stops writing until {@link #resume()}. The call returns immediately; the producer finishes
     * the round of records it is writing, if any. A producer paused before the simulator starts
     * starts paused.
     */
    void pause();

    /** Writes again after a {@link #pause()}. The call returns immediately. */
    void resume();

    /**
     * Removes the producer from the simulator. While the simulator runs, the call blocks until the
     * producer is stopped.
     *
     * @throws SimulationStateException if the producer is already removed
     */
    void remove();
}
