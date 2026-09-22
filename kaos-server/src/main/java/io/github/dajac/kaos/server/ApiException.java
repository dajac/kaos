package io.github.dajac.kaos.server;

import java.util.List;

import io.github.dajac.kaos.Violation;

/**
 * A request that the API refuses with a given status. {@link ProblemHandler} renders it as a problem
 * detail.
 */
final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final transient List<Violation> violations;

    private ApiException(int status, String message, List<Violation> violations) {
        super(message);
        this.status = status;
        this.violations = List.copyOf(violations);
    }

    /** A 400 for the given invalid fields. */
    static ApiException badRequest(String message, List<Violation> violations) {
        return new ApiException(400, message, violations);
    }

    /** A 400 for one invalid field. */
    static ApiException badRequest(String field, String message) {
        return badRequest("Invalid request: " + field + ": " + message, List.of(new Violation(field, message)));
    }

    /** A 404 for an unknown resource. */
    static ApiException notFound(String message) {
        return new ApiException(404, message, List.of());
    }

    /** A 409 for the wrong phase or a resource in use. */
    static ApiException conflict(String message) {
        return new ApiException(409, message, List.of());
    }

    int status() {
        return status;
    }

    List<Violation> violations() {
        return violations;
    }
}
