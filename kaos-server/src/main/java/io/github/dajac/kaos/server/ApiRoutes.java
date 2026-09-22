package io.github.dajac.kaos.server;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.ConsumerSpec;
import io.github.dajac.kaos.server.dto.ActionRequest;
import io.github.dajac.kaos.server.dto.AutopilotRequest;
import io.github.dajac.kaos.server.dto.GroupRequest;
import io.github.dajac.kaos.server.dto.MetaResponse;
import io.github.dajac.kaos.server.dto.ProducerRequest;
import io.github.dajac.kaos.server.dto.ProducerStateRequest;
import io.github.dajac.kaos.server.dto.SimulatorRequest;
import io.github.dajac.kaos.server.dto.TopicRequest;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

/**
 * The routes of the HTTP API v1. The handlers parse the request, call {@link SimulationService} and
 * write the response; the errors are left to {@link ProblemHandler}.
 *
 * <p>An operation that runs on the lifecycle thread answers 202 with the status. One that only flips
 * a flag answers 204.
 */
final class ApiRoutes {

    static final String API_V1 = "/api/v1";
    private static final String SIMULATOR = API_V1 + "/simulator";
    private static final String GROUP = SIMULATOR + "/groups/{groupId}";
    private static final String CONSUMER = GROUP + "/consumers/{id}";

    private final ObjectMapper mapper;
    private final SimulationService service;
    private final SseHub hub;
    private final MetaResponse meta;

    ApiRoutes(ObjectMapper mapper, SimulationService service, SseHub hub, String version) {
        this.mapper = mapper;
        this.service = service;
        this.hub = hub;
        this.meta = MetaResponse.of(version, ConfigMetadata.get());
    }

    /** Registers the routes. */
    void register(RoutesConfig routes) {
        routes.get(API_V1 + "/meta", ctx -> ctx.json(meta));

        routes.get(SIMULATOR, ctx -> ctx.json(service.describe()));
        routes.post(SIMULATOR, ctx -> accepted(ctx, service.start(body(ctx, SimulatorRequest.class).toSpec())));
        routes.delete(SIMULATOR, ctx -> {
            if (service.stop()) {
                ctx.status(202).json(service.status());
            } else {
                ctx.status(204);
            }
        });
        routes.get(SIMULATOR + "/snapshot", ctx -> ctx.json(service.snapshot()));
        routes.get(SIMULATOR + "/events", hub::serve);

        routes.post(SIMULATOR + "/topics", ctx -> {
            service.addTopic(body(ctx, TopicRequest.class).toSpec());
            accepted(ctx);
        });
        routes.delete(SIMULATOR + "/topics/{name}", ctx -> {
            service.removeTopic(ctx.pathParam("name"));
            accepted(ctx);
        });

        routes.post(SIMULATOR + "/producers", ctx -> {
            service.addProducer(body(ctx, ProducerRequest.class).toSpec());
            accepted(ctx);
        });
        routes.delete(SIMULATOR + "/producers/{id}", ctx -> {
            service.removeProducer(ctx.pathParam("id"));
            accepted(ctx);
        });
        routes.put(SIMULATOR + "/producers/{id}/state", ctx -> {
            var request = body(ctx, ProducerStateRequest.class);
            if (request.paused() == null) {
                throw ApiException.badRequest("paused", "is required");
            }
            service.setProducerPaused(ctx.pathParam("id"), request.paused());
            ctx.status(204);
        });

        routes.post(SIMULATOR + "/groups", ctx -> {
            service.addGroup(body(ctx, GroupRequest.class).toSpec());
            accepted(ctx);
        });
        routes.delete(GROUP, ctx -> {
            service.removeGroup(ctx.pathParam("groupId"));
            accepted(ctx);
        });
        routes.put(GROUP + "/autopilot", ctx -> {
            service.setAutopilot(ctx.pathParam("groupId"), body(ctx, AutopilotRequest.class));
            ctx.status(204);
        });

        routes.post(GROUP + "/consumers", ctx -> {
            service.addConsumer(ctx.pathParam("groupId"), body(ctx, ConsumerSpec.class));
            accepted(ctx);
        });
        routes.delete(CONSUMER, ctx -> {
            service.removeConsumer(ctx.pathParam("groupId"), ctx.pathParam("id"));
            accepted(ctx);
        });
        routes.put(CONSUMER + "/faults/{fault}", ctx -> {
            service.inject(ctx.pathParam("groupId"), ctx.pathParam("id"), ctx.pathParam("fault"));
            ctx.status(204);
        });
        routes.delete(CONSUMER + "/faults/{fault}", ctx -> {
            service.clear(ctx.pathParam("groupId"), ctx.pathParam("id"), ctx.pathParam("fault"));
            ctx.status(204);
        });
        routes.delete(CONSUMER + "/faults", ctx -> {
            service.clearFaults(ctx.pathParam("groupId"), ctx.pathParam("id"));
            ctx.status(204);
        });
        routes.post(CONSUMER + "/actions", this::action);
    }

    private void action(Context ctx) throws IOException {
        var request = body(ctx, ActionRequest.class);
        var groupId = ctx.pathParam("groupId");
        var id = ctx.pathParam("id");
        if (request.type() == null) {
            throw ApiException.badRequest("type", "is required");
        }
        switch (request.type()) {
            case "join" -> {
                service.join(groupId, id);
                ctx.status(204);
            }
            case "leave" -> {
                service.leave(groupId, id);
                ctx.status(204);
            }
            case "switch-protocol" -> {
                if (request.protocol() == null) {
                    throw ApiException.badRequest("protocol", "is required by switch-protocol");
                }
                service.switchProtocol(groupId, id, request.protocol());
                accepted(ctx);
            }
            default -> throw ApiException.badRequest("type", "must be join, leave or switch-protocol");
        }
    }

    private <T> T body(Context ctx, Class<T> type) throws IOException {
        var body = mapper.readValue(ctx.bodyAsBytes(), type);
        if (body == null) {
            throw ApiException.badRequest("The request body must be a JSON object", List.of());
        }
        return body;
    }

    private void accepted(Context ctx) {
        accepted(ctx, service.status());
    }

    private static void accepted(Context ctx, Object status) {
        ctx.status(202).json(status);
    }
}
