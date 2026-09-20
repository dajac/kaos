package io.github.dajac.kaos;

import io.github.dajac.kaos.internal.config.ConfigValidator;

/**
 * The simulator harness. Only the side-effect-free validation exists so far; the lifecycle and the
 * components are not built yet.
 */
public final class Simulator {
    private Simulator() {}

    /**
     * Checks a spec without any side effect. It runs the same checks as adding the components and
     * starting the simulator, and reports every violation at once.
     *
     * @throws InvalidConfigException if the spec is invalid
     */
    public static void validate(SimulatorSpec spec) {
        var violations = ConfigValidator.validate(spec);
        if (!violations.isEmpty()) {
            throw new InvalidConfigException(violations);
        }
    }
}
