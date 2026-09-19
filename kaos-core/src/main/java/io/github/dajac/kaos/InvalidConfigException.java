package io.github.dajac.kaos;

import java.util.List;
import java.util.stream.Collectors;

/** Thrown when a configuration is rejected. It carries every violation found, not only the first. */
public class InvalidConfigException extends SimulationException {
    private static final long serialVersionUID = 1L;

    private final transient List<Violation> violations;

    /**
     * @param violations the reasons of the rejection; at least one
     */
    public InvalidConfigException(List<Violation> violations) {
        super(describe(violations));
        this.violations = List.copyOf(violations);
    }

    /** The violations, in the order they were found. Never empty. */
    public List<Violation> violations() {
        return violations;
    }

    private static String describe(List<Violation> violations) {
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("An invalid configuration has at least one violation");
        }
        return violations.stream()
            .map(v -> v.field() + ": " + v.message())
            .collect(Collectors.joining("; ", "Invalid configuration: ", ""));
    }
}
