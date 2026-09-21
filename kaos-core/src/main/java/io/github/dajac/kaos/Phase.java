package io.github.dajac.kaos;

/** The lifecycle phase of a simulator. */
public enum Phase {
    /** Nothing runs. Components can be declared. */
    IDLE,
    /** {@code start()} is in progress. */
    STARTING,
    /** The simulation runs. */
    RUNNING,
    /** {@code stop()} is in progress. */
    STOPPING
}
