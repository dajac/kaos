package io.github.dajac.kaos.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.dajac.kaos.Violation;

/**
 * An RFC 9457 problem detail, served as {@code application/problem+json}.
 *
 * @param type always {@code about:blank}: the status code says what kind of problem it is
 * @param title the reason phrase of the status
 * @param status the HTTP status
 * @param detail a human-readable explanation of this occurrence
 * @param violations the invalid fields of the request, for a 400; left out when empty
 */
public record Problem(
    String type,
    String title,
    int status,
    String detail,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Violation> violations
) {

    public Problem {
        violations = List.copyOf(violations);
    }
}
