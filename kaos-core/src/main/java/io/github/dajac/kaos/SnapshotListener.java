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
 * Receives every new snapshot of a simulator.
 *
 * <p>All the listeners of a simulator run on its single publisher thread, in revision order, so a
 * listener returns quickly. The blocking methods of the simulator ({@code start}, {@code stop},
 * {@code close}, {@code with…}, {@code add…} and {@code awaitSnapshot}) wait for that thread, and
 * throw a {@link SimulationStateException} when a listener calls them; a listener that wants to
 * close the simulator hands that over to another thread. The other methods, such as
 * {@code snapshot()}, {@code pause()} and {@code resume()}, are fine. An exception thrown by a
 * listener is logged and does not affect the other listeners.
 */
@FunctionalInterface
public interface SnapshotListener {
    /** Called when the content of the snapshot changed. */
    void onSnapshot(SimulatorSnapshot snapshot);
}
