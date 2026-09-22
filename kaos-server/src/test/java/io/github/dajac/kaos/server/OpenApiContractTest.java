package io.github.dajac.kaos.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Error;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import io.github.dajac.kaos.AutopilotSnapshot;
import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.ConsumerOverrides;
import io.github.dajac.kaos.ConsumerSettings;
import io.github.dajac.kaos.ConsumerSnapshot;
import io.github.dajac.kaos.ConsumerSpec;
import io.github.dajac.kaos.ConsumerStatus;
import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.GroupSnapshot;
import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.GroupState;
import io.github.dajac.kaos.GroupType;
import io.github.dajac.kaos.PartitionRef;
import io.github.dajac.kaos.PartitionState;
import io.github.dajac.kaos.PartitionView;
import io.github.dajac.kaos.Phase;
import io.github.dajac.kaos.ProducerSnapshot;
import io.github.dajac.kaos.ProducerSpec;
import io.github.dajac.kaos.ProducerState;
import io.github.dajac.kaos.Protocol;
import io.github.dajac.kaos.Simulator;
import io.github.dajac.kaos.SimulatorSnapshot;
import io.github.dajac.kaos.SimulatorSpec;
import io.github.dajac.kaos.TopicSpec;
import io.github.dajac.kaos.Violation;
import io.github.dajac.kaos.server.dto.ActionRequest;
import io.github.dajac.kaos.server.dto.AutopilotRequest;
import io.github.dajac.kaos.server.dto.GroupRequest;
import io.github.dajac.kaos.server.dto.MetaResponse;
import io.github.dajac.kaos.server.dto.Problem;
import io.github.dajac.kaos.server.dto.ProducerRequest;
import io.github.dajac.kaos.server.dto.ProducerStateRequest;
import io.github.dajac.kaos.server.dto.SimulatorRequest;
import io.github.dajac.kaos.server.dto.SimulatorResponse;
import io.github.dajac.kaos.server.dto.StatusResponse;
import io.github.dajac.kaos.server.dto.TopicRequest;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The OpenAPI document {@code kaos-v1.yaml} against the server: the JSON the server's mapper writes
 * matches the schemas, the request bodies that match the schemas bind, every route is documented,
 * and the enums, ranges and defaults of the document are those of the Java model.
 */
class OpenApiContractTest {

    private static final String DOCUMENT = "classpath:" + ApiRoutes.OPENAPI_RESOURCE.substring(1);
    private static final String SCHEMAS = "#/components/schemas/";
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    /** Its bootstrap server does not resolve, so a start fails at once. */
    private static final String UNRESOLVABLE = "kaos-test.invalid:9092";

    private final ObjectMapper mapper = Json.mapper();
    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(Dialects.getOpenApi31());
    private final JsonNode document = readDocument();

    // Responses

    @Test
    void aFullSnapshotMatchesItsSchema() {
        assertValid("SimulatorSnapshot", snapshot());
    }

    @Test
    void theStatusMatchesItsSchema() {
        assertValid("Status", new StatusResponse(Phase.STARTING, "", List.of()));
        assertValid("Status", new StatusResponse(Phase.IDLE, "Cannot reach the cluster", List.of("a warning")));
    }

    @Test
    void theSimulatorWithItsSpecMatchesItsSchema() {
        assertValid("Simulator", new SimulatorResponse(Phase.RUNNING, spec(), "", List.of("a warning")));
    }

    @Test
    void theMetadataMatchesItsSchema() {
        assertValid("Meta", MetaResponse.of("1.2.3", ConfigMetadata.get()));
    }

    @Test
    void problemsMatchTheirSchema() {
        assertValid("Problem", new Problem("about:blank", "Bad Request", 400, "Invalid request", List.of(
            new Violation("topics[0].partitions", "must be between 1 and 100, got 0"),
            new Violation("groups[0].consumers[1].overrides.bogus", "is not a known property")
        )));
        assertValid("Problem", new Problem("about:blank", "Not Found", 404, "There is no group app", List.of()));
    }

    @Test
    void theSchemasRefuseWhatTheServerNeverWrites() {
        var snapshot = tree(snapshot());
        ((ObjectNode) snapshot.at("/groups/1/consumers/0/raw/assignment/0")).put("bogus", 1);
        assertThat(errors("SimulatorSnapshot", snapshot)).isNotEmpty();
        snapshot = tree(snapshot());
        ((ObjectNode) snapshot.at("/groups/0/consumers/0")).put("memberEpoch", -2);
        assertThat(errors("SimulatorSnapshot", snapshot)).isNotEmpty();

        var status = tree(new StatusResponse(Phase.RUNNING, "", List.of()));
        ((ObjectNode) status).put("phase", "running");
        assertThat(errors("Status", status)).isNotEmpty();

        var problem = tree(new Problem("about:blank", "Bad Request", 400, "Invalid", List.of()));
        ((ObjectNode) problem).remove("detail");
        assertThat(errors("Problem", problem)).isNotEmpty();
    }

    // Request bodies

    static Stream<Arguments> requests() {
        return Stream.of(
            Arguments.of("SimulatorRequest", SimulatorRequest.class,
                """
                    {"topics": [{"name": "orders", "partitions": 3}, {"name": "audit"}],
                     "producers": [{"id": "p-1", "topics": ["orders"], "ratePerPartition": 5.5}, {"topics": ["audit"]}],
                     "groups": [{"id": "app", "subscribedTopics": ["orders", "audit"],
                                 "sessionTimeoutMs": 10000, "heartbeatIntervalMs": 1000, "assignmentIntervalMs": 0,
                                 "consumerDefaults": {"protocol": "classic", "assignor": "range", "staticMember": true,
                                                      "rackId": "a", "pollTimeoutMs": 5, "pollDelayMs": 10,
                                                      "maxPollIntervalMs": 60000},
                                 "consumers": [{}, {"id": "mine", "overrides": {"protocol": "consumer", "assignor": "uniform"}}]}]}
                    """),
            Arguments.of("SimulatorRequest", SimulatorRequest.class, "{}"),
            Arguments.of("TopicRequest", TopicRequest.class, """
                {"name": "orders"}
                """),
            Arguments.of("ProducerRequest", ProducerRequest.class, """
                {"id": "", "topics": ["orders"]}
                """),
            Arguments.of("GroupRequest", GroupRequest.class, """
                {"id": "app", "subscribedTopics": ["orders"], "consumerDefaults": {"pollTimeoutMs": 5}}
                """),
            Arguments.of("ConsumerRequest", ConsumerSpec.class, """
                {"id": "c-9", "overrides": {"protocol": "classic", "staticMember": true, "rackId": "b",
                                            "pollTimeoutMs": 1, "pollDelayMs": 2, "maxPollIntervalMs": 3000}}
                """),
            Arguments.of("ConsumerRequest", ConsumerSpec.class, "{}"),
            Arguments.of("ProducerStateRequest", ProducerStateRequest.class, """
                {"paused": true}
                """),
            Arguments.of("AutopilotRequest", AutopilotRequest.class, """
                {"enabled": true, "minIntervalMs": 1000, "maxIntervalMs": 5000}
                """),
            Arguments.of("AutopilotRequest", AutopilotRequest.class, """
                {"enabled": false}
                """),
            Arguments.of("ActionRequest", ActionRequest.class, """
                {"type": "join"}
                """),
            Arguments.of("ActionRequest", ActionRequest.class, """
                {"type": "leave"}
                """),
            Arguments.of("ActionRequest", ActionRequest.class, """
                {"type": "switch-protocol", "protocol": "classic"}
                """)
        );
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("requests")
    void aRequestBodyThatMatchesItsSchemaBinds(String schema, Class<?> type, String body) throws Exception {
        assertValid(schema, mapper.readTree(body));
        assertThat(mapper.readValue(body, type)).isNotNull();
    }

    @Test
    void theSpecOfTheSimulatorMatchesTheStartRequest() {
        assertValid("SimulatorRequest", tree(spec()));
    }

    @Test
    void theRequestSchemasRefuseWhatTheServerRefuses() throws Exception {
        assertThat(errors("TopicRequest", mapper.readTree("{\"name\": \"orders\", \"bogus\": 1}"))).isNotEmpty();
        assertThat(errors("TopicRequest", mapper.readTree("{\"name\": \"orders\", \"partitions\": 0}"))).isNotEmpty();
        assertThat(errors("TopicRequest", mapper.readTree("{\"name\": \"..bad name\"}"))).isNotEmpty();
        assertThat(errors("ActionRequest", mapper.readTree("{\"type\": \"switch-protocol\"}"))).isNotEmpty();
        assertThat(errors("ActionRequest", mapper.readTree("{\"type\": \"jump\"}"))).isNotEmpty();
        assertThat(errors("ProducerStateRequest", mapper.readTree("{}"))).isNotEmpty();
    }

    // Live responses

    @Test
    void theServedResponsesMatchTheirSchemas() throws Exception {
        try (var server = new Server(new Simulator(UNRESOLVABLE)).start(0)) {
            var api = new ApiClient(server);

            assertResponse(api.get("/api/v1/meta"), 200, "Meta");
            assertResponse(api.get("/api/v1/simulator"), 200, "Simulator");
            assertResponse(api.get("/api/v1/simulator/snapshot"), 200, "SimulatorSnapshot");
            assertResponse(api.post("/api/v1/simulator", "{\"topics\": [{\"name\": \"orders\", \"partitions\": 0}]}"),
                400, "Problem");
            assertResponse(api.post("/api/v1/simulator", "not json"), 400, "Problem");
            assertResponse(api.delete("/api/v1/simulator/topics/orders"), 409, "Problem");
            assertResponse(api.get("/api/v1/nothing"), 404, "Problem");
            assertResponse(api.post("/api/v1/simulator", "{\"topics\": [{\"name\": \"orders\"}]}"), 202, "Status");
        }
    }

    @Test
    void anExceptionWithoutAMessageIsAProblemWithADetail() throws Exception {
        var app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.startup.startupWatcherEnabled = false;
            config.routes.get("/failure", ctx -> {
                throw new IllegalStateException();
            });
            config.routes.get("/invalid", ctx -> {
                throw new IllegalArgumentException();
            });
            new ProblemHandler(mapper).register(config.routes);
        }).start(0);
        try {
            var client = HttpClient.newHttpClient();
            for (var path : List.of("/failure", "/invalid")) {
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path)).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertResponse(response, path.equals("/failure") ? 500 : 400, "Problem");
            }
        } finally {
            app.stop();
        }
    }

    @Test
    void theEventsMatchTheSchemasOfTheDocument() throws Exception {
        var events = document.at("/paths/~1api~1v1~1simulator~1events/get/x-sse-events");
        var schemas = new TreeSet<String>();
        events.fieldNames().forEachRemaining(schemas::add);
        assertThat(schemas).containsExactly("snapshot", "status");

        try (var server = new Server(new Simulator(UNRESOLVABLE)).start(0)) {
            var stream = new ApiClient(server).events();
            var received = new ArrayList<ApiClient.Event>();
            for (int i = 0; i < 2; i++) {
                received.add(stream.poll(10, TimeUnit.SECONDS));
            }
            assertThat(received).extracting(ApiClient.Event::name).containsExactly("status", "snapshot");
            for (var event : received) {
                var ref = events.get(event.name()).get("$ref").asText();
                assertValid(ref.substring(SCHEMAS.length()), event.json());
            }
        }
    }

    @Test
    void theDocumentIsServedAsYaml() throws Exception {
        try (var server = new Server(new Simulator(UNRESOLVABLE)).start(0)) {
            var response = new ApiClient(server).get("/api/v1/openapi.yaml");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith(ApiRoutes.YAML_CONTENT_TYPE)
            );
            assertThat(new YAMLMapper().readTree(response.body())).isEqualTo(document);
        }
    }

    // The document against the code

    @Test
    void everyRouteIsDocumentedAndEveryDocumentedRouteExists() {
        var documented = new TreeSet<String>();
        document.get("paths").fields().forEachRemaining(path -> path.getValue().fieldNames().forEachRemaining(key -> {
            if (METHODS.contains(key)) {
                documented.add(key.toUpperCase() + " " + path.getKey());
            }
        }));

        var registered = new TreeSet<String>();
        try (var service = new SimulationService(new Simulator(UNRESOLVABLE))) {
            var hub = new SseHub(mapper, service::snapshot, service::status, Duration.ofSeconds(1));
            var routes = new ApiRoutes(mapper, service, hub, "test");
            var app = Javalin.create(config -> routes.register(config.routes));
            app.unsafe.internalRouter.allHttpHandlers().forEach(
                handler -> registered.add(handler.endpoint.method.name() + " " + handler.endpoint.path)
            );
        }

        assertThat(registered).isNotEmpty();
        assertThat(documented).isEqualTo(registered);
    }

    @Test
    void theEnumsAreThoseOfTheJavaModel() {
        assertEnum("Protocol", Arrays.stream(Protocol.values()).map(Protocol::id).toList());
        assertEnum("FaultId", Fault.all().stream().map(Fault::id).toList());
        assertEnum("Phase", names(Phase.values()));
        assertEnum("GroupType", names(GroupType.values()));
        assertEnum("GroupState", names(GroupState.values()));
        assertEnum("PartitionState", names(PartitionState.values()));
        assertEnum("ConsumerStatus", names(ConsumerStatus.values()));
        assertEnum("ProducerState", names(ProducerState.values()));
        assertThat(strings(schema("ConfigField").at("/properties/scope/enum")))
            .isEqualTo(names(ConfigMetadata.Scope.values()));
        assertThat(strings(schema("ConfigField").at("/properties/type/enum")))
            .isEqualTo(names(ConfigMetadata.Type.values()));
    }

    @Test
    void theNameRuleIsThatOfTheMetadata() {
        var rule = ConfigMetadata.get().nameRule();
        var name = schema("Name");
        assertThat(name.get("pattern").asText()).isEqualTo("^" + rule.pattern() + "$");
        assertThat(name.get("maxLength").asInt()).isEqualTo(rule.maxLength());
        assertThat(schema("OptionalName").get("maxLength").asInt()).isEqualTo(rule.maxLength());
    }

    @Test
    void theRangesAndDefaultsAreThoseOfTheMetadata() {
        var schemas = Map.of(
            ConfigMetadata.Scope.TOPIC, List.of("TopicRequest"),
            ConfigMetadata.Scope.PRODUCER, List.of("ProducerRequest"),
            ConfigMetadata.Scope.GROUP, List.of("GroupRequest"),
            ConfigMetadata.Scope.CONSUMER, List.of("ConsumerSettingsRequest", "ConsumerOverrides")
        );
        var checked = new HashSet<String>();
        for (var field : ConfigMetadata.get().fields()) {
            if (field.scope() == ConfigMetadata.Scope.SIMULATOR) {
                var topics = schema("SimulatorRequest").at("/properties/topics");
                assertThat(topics.get("maxItems").asInt()).isEqualTo(field.range().orElseThrow().max());
                checked.add(field.key());
                continue;
            }
            for (var schemaName : schemas.get(field.scope())) {
                var property = schema(schemaName).at("/properties/" + field.key());
                if (property.isMissingNode()) {
                    continue;
                }
                var context = schemaName + "." + field.key();
                field.range().ifPresent(range -> {
                    assertThat(property.get("minimum").asInt()).as(context).isEqualTo(range.min());
                    assertThat(property.get("maximum").asInt()).as(context).isEqualTo(range.max());
                    checked.add(field.key());
                });
                if (property.has("default")) {
                    assertThat(property.get("default")).as(context).isEqualTo(tree(field.defaultValue()));
                }
            }
        }
        var ranged = ConfigMetadata.get().fields().stream()
            .filter(field -> field.range().isPresent())
            .map(ConfigMetadata.Field::key)
            .collect(Collectors.toSet());
        assertThat(checked).isEqualTo(ranged);
    }

    // Fixtures

    /** A running simulation with a consumer group and a classic group, faults, lag and a failed consumer. */
    static SimulatorSnapshot snapshot() {
        var orders0 = new PartitionRef("orders", 0);
        var orders1 = new PartitionRef("orders", 1);
        var orders2 = new PartitionRef("orders", 2);
        var reconciling = new ConsumerSnapshot(
            "consumer-1", "app-consumer-1", Protocol.CONSUMER, true, List.of(), "m-1", "", 12, -1,
            ConsumerStatus.RECONCILING, ConsumerStatus.RECONCILING, "",
            List.of(
                new PartitionView("orders", 0, PartitionState.ASSIGNED),
                new PartitionView("orders", 1, PartitionState.REVOKING)
            ),
            new ConsumerSnapshot.Raw(List.of(orders0, orders1), List.of(orders0), List.of(orders0, orders1))
        );
        var frozen = new ConsumerSnapshot(
            "consumer-2", "app-consumer-2", Protocol.CONSUMER, true, List.of(Fault.freeze(), Fault.networkIsolation()),
            "m-2", "app-consumer-2", 11, -1, ConsumerStatus.JOINING, ConsumerStatus.FROZEN, "",
            List.of(new PartitionView("orders", 2, PartitionState.PENDING)),
            new ConsumerSnapshot.Raw(List.of(), List.of(orders1, orders2), List.of())
        );
        var consumerGroup = new GroupSnapshot(
            "app", GroupType.CONSUMER, GroupState.RECONCILING, 12, 12, -1, List.of("orders"),
            new AutopilotSnapshot(true, 1_000, 5_000), Map.of("orders", List.of(0L, 17L, -1L)),
            List.of(reconciling, frozen)
        );

        var audit0 = new PartitionRef("audit", 0);
        var stable = new ConsumerSnapshot(
            "consumer-1", "legacy-consumer-1", Protocol.CLASSIC, true, List.of(Fault.connectionReset()), "m-3", "", -1,
            4, ConsumerStatus.STABLE, ConsumerStatus.STABLE, "",
            List.of(new PartitionView("audit", 0, PartitionState.ASSIGNED)),
            new ConsumerSnapshot.Raw(List.of(audit0), List.of(audit0), List.of(audit0))
        );
        var failed = new ConsumerSnapshot(
            "consumer-2", "legacy-consumer-2", Protocol.CLASSIC, false, List.of(), "", "", -1, -1,
            ConsumerStatus.STOPPED, ConsumerStatus.FAILED, "The assignor bogus is not supported", List.of(),
            new ConsumerSnapshot.Raw(List.of(), List.of(), List.of())
        );
        var classicGroup = new GroupSnapshot(
            "legacy", GroupType.CLASSIC, GroupState.STABLE, -1, -1, 4, List.of("audit"),
            new AutopilotSnapshot(false, 2_000, 10_000), Map.of("audit", List.of(3L)), List.of(stable, failed)
        );

        return new SimulatorSnapshot(
            42, Phase.RUNNING, true, "",
            List.of(new TopicSpec("orders", 3), new TopicSpec("audit", 1)),
            List.of(
                new ProducerSnapshot("p-1", List.of("orders"), ProducerState.RUNNING, 2.0),
                new ProducerSnapshot("p-2", List.of("audit"), ProducerState.PAUSED, 0.5)
            ),
            List.of(consumerGroup, classicGroup)
        );
    }

    /** A spec with consumer defaults, a consumer that overrides every setting and consumers that inherit. */
    static SimulatorSpec spec() {
        var overrides = new ConsumerOverrides(
            Optional.of(Protocol.CONSUMER), Optional.of("uniform"), Optional.of(true), Optional.of("a"),
            OptionalInt.of(10), OptionalInt.of(20), OptionalInt.of(30_000)
        );
        var group = new GroupSpec(
            "app", List.of("orders"), 10_000, 1_000, 0,
            new ConsumerSettings(Protocol.CLASSIC, "range", false, "", 0, 1_000, 300_000),
            List.of(new ConsumerSpec("consumer-1", overrides, null), ConsumerSpec.inheriting())
        );
        return new SimulatorSpec(
            List.of(new TopicSpec("orders", 3)),
            List.of(ProducerSpec.of(p -> p.id("p-1").topics("orders").ratePerPartition(5))),
            List.of(group)
        );
    }

    // Helpers

    private void assertValid(String schema, Object value) {
        var node = value instanceof JsonNode tree ? tree : tree(value);
        assertThat(errors(schema, node)).as("%s against %s", node, schema).isEmpty();
    }

    private void assertResponse(HttpResponse<String> response, int status, String schema) throws IOException {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var type = status >= 400 ? ProblemHandler.CONTENT_TYPE : "application/json";
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
            header -> assertThat(header).startsWith(type)
        );
        assertValid(schema, mapper.readTree(response.body()));
    }

    private List<Error> errors(String schema, JsonNode node) {
        return registry.getSchema(SchemaLocation.of(DOCUMENT + SCHEMAS + schema)).validate(node);
    }

    /** The JSON the server writes for the value. */
    private JsonNode tree(Object value) {
        try {
            return mapper.readTree(mapper.writeValueAsString(value));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode schema(String name) {
        var schema = document.at("/components/schemas/" + name);
        assertThat(schema.isMissingNode()).as("schema %s", name).isFalse();
        return schema;
    }

    private void assertEnum(String schema, List<String> values) {
        assertThat(strings(schema(schema).get("enum"))).as(schema).isEqualTo(values);
    }

    private static List<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static JsonNode readDocument() {
        try (var in = OpenApiContractTest.class.getResourceAsStream(ApiRoutes.OPENAPI_RESOURCE)) {
            return new YAMLMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
