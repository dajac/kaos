package io.github.dajac.kaos;

/**
 * Receives every new snapshot of a simulator.
 *
 * <p>All the listeners of a simulator run on its single publisher thread, in revision order. A
 * listener returns quickly, and never calls a blocking method of the simulator ({@code start},
 * {@code stop}, {@code close}, {@code add…}, {@code awaitSnapshot}): these wait for the publisher
 * thread. An exception thrown by a listener is logged and does not affect the other listeners.
 */
@FunctionalInterface
public interface SnapshotListener {
    /** Called when the content of the snapshot changed. */
    void onSnapshot(SimulatorSnapshot snapshot);
}
