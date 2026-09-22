package io.github.dajac.kaos.server;

import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.InvalidNullException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import io.github.dajac.kaos.InvalidConfigException;
import io.github.dajac.kaos.SimulationStateException;
import io.github.dajac.kaos.Violation;
import io.github.dajac.kaos.server.dto.Problem;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders every error of the API as an RFC 9457 problem detail ({@code application/problem+json}):
 *
 * <ul>
 *   <li>400 for a body that does not bind, an {@link InvalidConfigException} and any other invalid
 *       argument, with the invalid fields as {@code violations[{field, message}]};</li>
 *   <li>404 for an unknown resource or route;</li>
 *   <li>409 for the wrong phase or a resource in use, including a {@link SimulationStateException};</li>
 *   <li>500 for anything else, which is logged.</li>
 * </ul>
 */
final class ProblemHandler {

    static final String CONTENT_TYPE = "application/problem+json";

    private static final Logger LOG = LoggerFactory.getLogger(ProblemHandler.class);

    private final ObjectMapper mapper;

    ProblemHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Registers the handlers. */
    void register(RoutesConfig routes) {
        routes.exception(ApiException.class, (e, ctx) -> write(ctx, e.status(), e.getMessage(), e.violations()));
        routes.exception(InvalidConfigException.class, (e, ctx) -> write(ctx, 400, e.getMessage(), e.violations()));
        routes.exception(JsonProcessingException.class, (e, ctx) -> unreadableBody(ctx, e));
        routes.exception(IllegalArgumentException.class, (e, ctx) -> write(ctx, 400, e.getMessage(), List.of()));
        routes.exception(SimulationStateException.class, (e, ctx) -> write(ctx, 409, e.getMessage(), List.of()));
        routes.exception(HttpResponseException.class, (e, ctx) -> write(ctx, e.getStatus(), e.getMessage(), List.of()));
        routes.exception(Exception.class, (e, ctx) -> {
            LOG.error("Request {} {} failed", ctx.method(), ctx.path(), e);
            write(ctx, 500, e.getMessage() == null ? e.toString() : e.getMessage(), List.of());
        });
        // Javalin answers a route that does not exist without an exception.
        routes.error(404, ctx -> {
            if (!CONTENT_TYPE.equals(ctx.res().getContentType())) {
                write(ctx, 404, "No resource at " + ctx.path(), List.of());
            }
        });
    }

    private void unreadableBody(Context ctx, JsonProcessingException e) {
        if (
            !(e instanceof JsonMappingException mapping)
                || mapping.getPath().isEmpty()
                || mapping.getCause() instanceof StreamReadException
        ) {
            var detail = e instanceof MismatchedInputException && ctx.bodyAsBytes().length == 0
                ? "The request body is missing"
                : "The request body is not valid JSON: " + e.getOriginalMessage();
            write(ctx, 400, detail, List.of());
            return;
        }
        var violation = new Violation(path(mapping), reason(mapping));
        write(ctx, 400, "Invalid request: " + violation.field() + ": " + violation.message(), List.of(violation));
    }

    /** The path of the value, as in the violations of the core: {@code groups[0].consumers[1].id}. */
    private static String path(JsonMappingException e) {
        var path = e.getPath().stream()
            .map(ref -> ref.getFieldName() != null ? "." + ref.getFieldName() : "[" + ref.getIndex() + "]")
            .collect(Collectors.joining());
        return path.startsWith(".") ? path.substring(1) : path;
    }

    private static String reason(JsonMappingException e) {
        if (e instanceof UnrecognizedPropertyException) {
            return "is not a known property";
        }
        if (e instanceof InvalidNullException) {
            return "must not be null";
        }
        if (e instanceof InvalidFormatException format) {
            return "has an invalid value: " + format.getValue();
        }
        if (e instanceof ValueInstantiationException && e.getCause() != null) {
            return e.getCause().getMessage();
        }
        return "has an invalid value";
    }

    private void write(Context ctx, int status, String detail, List<Violation> violations) {
        var httpStatus = HttpStatus.forStatus(status);
        // The detail is always present: an exception without a message falls back to the title.
        var problem = new Problem("about:blank", httpStatus.getMessage(), status,
            detail == null ? httpStatus.getMessage() : detail, violations);
        String body;
        try {
            body = mapper.writeValueAsString(problem);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        ctx.status(status).contentType(CONTENT_TYPE).result(body);
    }
}
