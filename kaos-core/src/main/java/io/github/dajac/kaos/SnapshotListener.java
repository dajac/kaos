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
