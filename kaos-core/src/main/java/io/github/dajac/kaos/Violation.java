package io.github.dajac.kaos;

import java.util.Objects;

/**
 * One reason why a configuration is invalid.
 *
 * @param field the path of the offending value, such as {@code groups[0].sessionTimeoutMs}
 * @param message what is wrong with it
 */
public record Violation(String field, String message) {
    public Violation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
    }
}
