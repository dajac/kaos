package io.github.dajac.kaos;

/** The base class of the exceptions thrown by the simulator. */
public class SimulationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SimulationException(String message) {
        super(message);
    }

    public SimulationException(String message, Throwable cause) {
        super(message, cause);
    }
}
