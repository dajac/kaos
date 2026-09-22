package io.github.dajac.kaos;

/** The registration of a listener. Closing it removes the listener. */
public interface Registration extends AutoCloseable {
    /** Removes the listener. The call is idempotent. */
    @Override
    void close();
}
