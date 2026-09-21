package io.github.dajac.kaos;

/**
 * A topic created and owned by a simulator. The simulator creates it when it starts and deletes it
 * when it stops.
 */
public interface Topic {
    /** The topic name. */
    String name();

    /** The number of partitions. */
    int partitions();
}
