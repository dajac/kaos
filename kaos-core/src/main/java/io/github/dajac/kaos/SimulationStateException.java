package io.github.dajac.kaos;

/**
 * Thrown when a call does not fit the current state of the simulator, such as starting a simulator
 * that already runs.
 */
public class SimulationStateException extends SimulationException {
    private static final long serialVersionUID = 1L;

    public SimulationStateException(String message) {
        super(message);
    }
}
