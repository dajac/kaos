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

/**
 * A topic created and owned by a simulator. The simulator creates it when it starts, or at once when
 * it is added to a running simulator, and deletes it when it stops or when the topic is removed.
 */
public interface Topic {
    /** The topic name. */
    String name();

    /** The number of partitions. */
    int partitions();

    /**
     * Removes the topic from the simulator. While the simulator runs, the call blocks until the
     * topic is deleted from the cluster.
     *
     * @throws SimulationStateException if a producer or a group still uses the topic, or if the
     *     topic is already removed
     * @throws SimulationException if the topic cannot be deleted
     */
    void remove();
}
