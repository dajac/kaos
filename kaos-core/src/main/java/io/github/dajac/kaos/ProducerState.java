package io.github.dajac.kaos;

/** The state of a load generator. */
public enum ProducerState {
    /**
     * The producer is declared and does not run: the simulator has not started it, or has stopped
     * it, or its loop ended on an unexpected error, which is logged.
     */
    STOPPED,
    /** The producer writes records. */
    RUNNING,
    /** The producer runs and writes nothing until it is resumed. */
    PAUSED
}
