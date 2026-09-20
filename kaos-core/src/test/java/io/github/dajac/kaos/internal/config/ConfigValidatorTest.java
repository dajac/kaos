package io.github.dajac.kaos.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.ConfigMetadata.Field;
import io.github.dajac.kaos.ConfigMetadata.Range;
import io.github.dajac.kaos.ConsumerOverrides;
import io.github.dajac.kaos.ConsumerSettings;
import io.github.dajac.kaos.ConsumerSpec;
import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.ProducerSpec;
import io.github.dajac.kaos.Protocol;
import io.github.dajac.kaos.SimulatorSpec;
import io.github.dajac.kaos.TopicSpec;
import io.github.dajac.kaos.Violation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

class ConfigValidatorTest {
    private static final ConsumerSettings DEFAULTS = ConsumerSettings.defaults();

    private static GroupSpec group(String id, ConsumerSettings defaults, ConsumerSpec... consumers) {
        return new GroupSpec(id, List.of("orders"), 45_000, 5_000, 1_000, defaults, List.of(consumers));
    }

    private static SimulatorSpec withGroup(GroupSpec group) {
        return new SimulatorSpec(List.of(TopicSpec.of("orders")), List.of(), List.of(group));
    }

    private static ConsumerSettings settings(Protocol protocol, String assignor) {
        return new ConsumerSettings(protocol, assignor, false, "", 0, 1_000, 300_000);
    }

    private static ConsumerSpec overriding(Optional<Protocol> protocol, Optional<String> assignor) {
        return ConsumerSpec.of(new ConsumerOverrides(
            protocol, assignor, Optional.empty(), Optional.empty(),
            OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty()
        ));
    }

    private static ConsumerSpec withId(String id) {
        return new ConsumerSpec(id, ConsumerOverrides.none(), null);
    }

    @Test
    void acceptsAFullSpec() {
        var spec = new SimulatorSpec(
            List.of(TopicSpec.of("orders"), new TopicSpec("payments", 12)),
            List.of(ProducerSpec.of(p -> p.topics("orders", "payments"))),
            List.of(
                GroupSpec.of("next", g -> g
                    .subscribe("orders")
                    .consumerDefaults(c -> c.protocol(Protocol.CONSUMER).assignor("uniform"))
                    .consumers(3)
                ),
                GroupSpec.of("legacy", g -> g
                    .subscribe("orders", "payments")
                    .consumerDefaults(c -> c.protocol(Protocol.CLASSIC).assignor("cooperative-sticky"))
                    .consumer(c -> c.rackId("a"))
                    .consumers(2)
                )
            )
        );

        assertThat(ConfigValidator.validate(spec)).isEmpty();
    }

    @Test
    void acceptsAnEmptySpec() {
        assertThat(ConfigValidator.validate(new SimulatorSpec(List.of(), List.of(), List.of()))).isEmpty();
    }

    /**
     * Each ranged field with the path of its violation and a function that builds a spec holding
     * the given value in that field.
     */
    static Stream<Arguments> rangedFields() {
        return Stream.of(
            rangedField(ConfigMetadata.PARTITIONS, "topics[0].partitions", value ->
                new SimulatorSpec(List.of(new TopicSpec("orders", value)), List.of(), List.of())
            ),
            rangedField(ConfigMetadata.SESSION_TIMEOUT_MS, "groups[0].sessionTimeoutMs", value ->
                withGroup(new GroupSpec("g", List.of("orders"), value, 5_000, 1_000, DEFAULTS, List.of()))
            ),
            rangedField(ConfigMetadata.HEARTBEAT_INTERVAL_MS, "groups[0].heartbeatIntervalMs", value ->
                withGroup(new GroupSpec("g", List.of("orders"), 45_000, value, 1_000, DEFAULTS, List.of()))
            ),
            rangedField(ConfigMetadata.ASSIGNMENT_INTERVAL_MS, "groups[0].assignmentIntervalMs", value ->
                withGroup(new GroupSpec("g", List.of("orders"), 45_000, 5_000, value, DEFAULTS, List.of()))
            ),
            rangedField(ConfigMetadata.POLL_TIMEOUT_MS, "groups[0].consumerDefaults.pollTimeoutMs", value ->
                withGroup(group("g", new ConsumerSettings(Protocol.CONSUMER, "", false, "", value, 1_000, 300_000)))
            ),
            rangedField(ConfigMetadata.POLL_DELAY_MS, "groups[0].consumerDefaults.pollDelayMs", value ->
                withGroup(group("g", new ConsumerSettings(Protocol.CONSUMER, "", false, "", 0, value, 300_000)))
            ),
            rangedField(ConfigMetadata.MAX_POLL_INTERVAL_MS, "groups[0].consumerDefaults.maxPollIntervalMs", value ->
                withGroup(group("g", new ConsumerSettings(Protocol.CONSUMER, "", false, "", 0, 1_000, value)))
            ),
            rangedField(ConfigMetadata.POLL_TIMEOUT_MS, "groups[0].consumers[0].overrides.pollTimeoutMs", value ->
                withGroup(group("g", DEFAULTS, intOverrides(OptionalInt.of(value), OptionalInt.empty(), OptionalInt.empty())))
            ),
            rangedField(ConfigMetadata.POLL_DELAY_MS, "groups[0].consumers[0].overrides.pollDelayMs", value ->
                withGroup(group("g", DEFAULTS, intOverrides(OptionalInt.empty(), OptionalInt.of(value), OptionalInt.empty())))
            ),
            rangedField(ConfigMetadata.MAX_POLL_INTERVAL_MS, "groups[0].consumers[0].overrides.maxPollIntervalMs", value ->
                withGroup(group("g", DEFAULTS, intOverrides(OptionalInt.empty(), OptionalInt.empty(), OptionalInt.of(value))))
            )
        );
    }

    private static Arguments rangedField(Field field, String path, IntFunction<SimulatorSpec> spec) {
        return Arguments.of(path, field, spec);
    }

    private static ConsumerSpec intOverrides(OptionalInt pollTimeoutMs, OptionalInt pollDelayMs, OptionalInt maxPollIntervalMs) {
        return ConsumerSpec.of(new ConsumerOverrides(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            pollTimeoutMs, pollDelayMs, maxPollIntervalMs
        ));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rangedFields")
    void enforcesTheRangeOfEachField(String path, Field field, IntFunction<SimulatorSpec> spec) {
        var range = field.range().orElseThrow();

        assertThat(ConfigValidator.validate(spec.apply(range.min()))).isEmpty();
        assertThat(ConfigValidator.validate(spec.apply(range.max()))).isEmpty();
        assertThat(ConfigValidator.validate(spec.apply(field.intDefault()))).isEmpty();
        assertThat(ConfigValidator.validate(spec.apply(range.min() - 1))).containsExactly(new Violation(
            path, "must be between " + range.min() + " and " + range.max() + ", got " + (range.min() - 1)
        ));
        assertThat(ConfigValidator.validate(spec.apply(range.max() + 1))).containsExactly(new Violation(
            path, "must be between " + range.min() + " and " + range.max() + ", got " + (range.max() + 1)
        ));
    }

    @Test
    void everyRangedSpecFieldIsCovered() {
        // numTopics is a count, not a spec field; it has its own test below.
        var covered = Set.of(
            "partitions", "sessionTimeoutMs", "heartbeatIntervalMs", "assignmentIntervalMs",
            "pollTimeoutMs", "pollDelayMs", "maxPollIntervalMs", "numTopics"
        );

        assertThat(ConfigMetadata.get().fields().stream().filter(f -> f.range().isPresent()).map(Field::key))
            .containsExactlyInAnyOrderElementsOf(covered);
    }

    @Test
    void enforcesTheMaximumNumberOfTopics() {
        var twenty = IntStream.rangeClosed(1, 20).mapToObj(i -> TopicSpec.of("t-" + i)).toList();
        var twentyOne = IntStream.rangeClosed(1, 21).mapToObj(i -> TopicSpec.of("t-" + i)).toList();

        assertThat(ConfigValidator.validate(new SimulatorSpec(twenty, List.of(), List.of()))).isEmpty();
        assertThat(ConfigValidator.validate(new SimulatorSpec(twentyOne, List.of(), List.of())))
            .containsExactly(new Violation("topics", "must have at most 20 topics, got 21"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"range", "roundrobin", "sticky", "cooperative-sticky", ""})
    void acceptsTheKnownClassicAssignors(String assignor) {
        assertThat(ConfigValidator.validate(withGroup(group("g", settings(Protocol.CLASSIC, assignor))))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"uniform", "my-custom-assignor", "Range", " "})
    void rejectsAnUnknownClassicAssignorInTheGroupDefaults(String assignor) {
        var spec = withGroup(group("g", settings(Protocol.CLASSIC, assignor), ConsumerSpec.inheriting()));

        // The inheriting consumer does not repeat the violation of the defaults.
        assertThat(ConfigValidator.validate(spec)).containsExactly(new Violation(
            "groups[0].consumerDefaults.assignor",
            "unknown assignor '" + assignor + "' for the classic protocol; "
                + "expected one of: range, roundrobin, sticky, cooperative-sticky"
        ));
    }

    @Test
    void rejectsAnUnknownClassicAssignorInAnOverride() {
        var spec = withGroup(group(
            "g", settings(Protocol.CLASSIC, "range"), overriding(Optional.empty(), Optional.of("uniform"))
        ));

        assertThat(ConfigValidator.validate(spec)).containsExactly(new Violation(
            "groups[0].consumers[0].overrides.assignor",
            "unknown assignor 'uniform' for the classic protocol; "
                + "expected one of: range, roundrobin, sticky, cooperative-sticky"
        ));
    }

    @Test
    void reportsAnOverriddenAssignorOnTheAssignorEvenWhenTheProtocolIsOverriddenToo() {
        var spec = withGroup(group(
            "g", settings(Protocol.CONSUMER, "uniform"), overriding(Optional.of(Protocol.CLASSIC), Optional.of("uniform"))
        ));

        assertThat(ConfigValidator.validate(spec)).extracting(Violation::field)
            .containsExactly("groups[0].consumers[0].overrides.assignor");
    }

    @Test
    void reportsAClassicOverrideThatInheritsAConsumerOnlyAssignorOnTheProtocol() {
        var spec = withGroup(group(
            "g", settings(Protocol.CONSUMER, "uniform"), overriding(Optional.of(Protocol.CLASSIC), Optional.empty())
        ));

        assertThat(ConfigValidator.validate(spec)).containsExactly(new Violation(
            "groups[0].consumers[0].overrides.protocol",
            "the inherited assignor 'uniform' is unknown for the classic protocol; "
                + "override the assignor with one of: range, roundrobin, sticky, cooperative-sticky"
        ));
    }

    @Test
    void acceptsAClassicOverrideWithItsOwnAssignor() {
        var spec = withGroup(group(
            "g", settings(Protocol.CONSUMER, "uniform"), overriding(Optional.of(Protocol.CLASSIC), Optional.of("sticky"))
        ));

        assertThat(ConfigValidator.validate(spec)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"uniform", "range", "my-custom-assignor", "com.example.Assignor", ""})
    void acceptsAnyAssignorForTheConsumerProtocol(String assignor) {
        var inDefaults = withGroup(group("g", settings(Protocol.CONSUMER, assignor)));
        var inOverride = withGroup(group(
            "g", settings(Protocol.CLASSIC, "range"), overriding(Optional.of(Protocol.CONSUMER), Optional.of(assignor))
        ));

        assertThat(ConfigValidator.validate(inDefaults)).isEmpty();
        assertThat(ConfigValidator.validate(inOverride)).isEmpty();
    }

    @Test
    void rejectsAGroupSubscribedToAnUnknownTopic() {
        var group = GroupSpec.of("g", g -> g.subscribe("orders", "missing"));

        assertThat(ConfigValidator.validate(withGroup(group)))
            .containsExactly(new Violation("groups[0].subscribedTopics[1]", "unknown topic 'missing'"));
    }

    @Test
    void rejectsAProducerWritingToAnUnknownTopic() {
        var spec = new SimulatorSpec(
            List.of(TopicSpec.of("orders")), List.of(ProducerSpec.of(p -> p.topics("missing", "orders"))), List.of()
        );

        assertThat(ConfigValidator.validate(spec))
            .containsExactly(new Violation("producers[0].topics[0]", "unknown topic 'missing'"));
    }

    @Test
    void rejectsAGroupOrAProducerWithoutTopics() {
        var spec = new SimulatorSpec(
            List.of(), List.of(ProducerSpec.of(p -> {})), List.of(GroupSpec.of("g", g -> {}))
        );

        assertThat(ConfigValidator.validate(spec)).containsExactly(
            new Violation("producers[0].topics", "must name at least one topic"),
            new Violation("groups[0].subscribedTopics", "must name at least one topic")
        );
    }

    @Test
    void rejectsATopicReferencedTwice() {
        var group = GroupSpec.of("g", g -> g.subscribe("orders", "orders"));

        assertThat(ConfigValidator.validate(withGroup(group)))
            .containsExactly(new Violation("groups[0].subscribedTopics[1]", "duplicate topic 'orders'"));
    }

    @Test
    void rejectsDuplicateTopicNames() {
        var spec = new SimulatorSpec(
            List.of(TopicSpec.of("orders"), TopicSpec.of("orders")), List.of(), List.of()
        );

        assertThat(ConfigValidator.validate(spec))
            .containsExactly(new Violation("topics[1].name", "duplicate topic 'orders'"));
    }

    @Test
    void rejectsDuplicateProducerIds() {
        var spec = new SimulatorSpec(
            List.of(TopicSpec.of("orders")),
            List.of(
                ProducerSpec.of(p -> p.id("load").topics("orders")),
                ProducerSpec.of(p -> p.id("load").topics("orders"))
            ),
            List.of()
        );

        assertThat(ConfigValidator.validate(spec))
            .containsExactly(new Violation("producers[1].id", "duplicate producer 'load'"));
    }

    @Test
    void rejectsDuplicateGroupIds() {
        var spec = new SimulatorSpec(
            List.of(TopicSpec.of("orders")),
            List.of(),
            List.of(GroupSpec.of("g", g -> g.subscribe("orders")), GroupSpec.of("g", g -> g.subscribe("orders")))
        );

        assertThat(ConfigValidator.validate(spec))
            .containsExactly(new Violation("groups[1].id", "duplicate group 'g'"));
    }

    @Test
    void rejectsDuplicateConsumerIdsWithinAGroup() {
        var spec = withGroup(group("g", DEFAULTS, withId("consumer-0"), withId("consumer-0")));

        assertThat(ConfigValidator.validate(spec))
            .containsExactly(new Violation("groups[0].consumers[1].id", "duplicate consumer 'consumer-0'"));
    }

    @Test
    void acceptsTheSameConsumerIdInTwoGroups() {
        var spec = new SimulatorSpec(
            List.of(TopicSpec.of("orders")),
            List.of(),
            List.of(group("a", DEFAULTS, withId("consumer-0")), group("b", DEFAULTS, withId("consumer-0")))
        );

        assertThat(ConfigValidator.validate(spec)).isEmpty();
    }

    @Test
    void unassignedIdsAreNotDuplicates() {
        var spec = new SimulatorSpec(
            List.of(TopicSpec.of("orders")),
            List.of(ProducerSpec.of(p -> p.topics("orders")), ProducerSpec.of(p -> p.topics("orders"))),
            List.of(GroupSpec.of("g", g -> g.subscribe("orders").consumers(2)))
        );

        assertThat(ConfigValidator.validate(spec)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "with space", "slash/name", "é"})
    void rejectsAnIllegalTopicName(String name) {
        assertThat(ConfigValidator.validateTopic(new TopicSpec(name, 1))).extracting(Violation::field)
            .containsExactly("name");
    }

    @Test
    void enforcesTheTopicNameLength() {
        assertThat(ConfigValidator.validateTopic(new TopicSpec("a".repeat(249), 1))).isEmpty();
        assertThat(ConfigValidator.validateTopic(new TopicSpec("a".repeat(250), 1)))
            .containsExactly(new Violation("name", "must have at most 249 characters"));
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "with space", "slash/name", "é", "a?b", "a#b"})
    void rejectsAnIllegalId(String id) {
        var message = "must contain only ASCII letters, digits, '.', '_' and '-', and not be '.' or '..'";
        var spec = new SimulatorSpec(
            List.of(TopicSpec.of("orders")),
            List.of(new ProducerSpec(id, List.of("orders"), 1.0)),
            List.of(group(id, DEFAULTS, withId(id)))
        );

        assertThat(ConfigValidator.validate(spec)).containsExactly(
            new Violation("producers[0].id", message),
            new Violation("groups[0].id", message),
            new Violation("groups[0].consumers[0].id", message)
        );
    }

    @Test
    void enforcesTheIdLength() {
        var longest = "a".repeat(249);
        var tooLong = "a".repeat(250);

        assertThat(ConfigValidator.validate(new SimulatorSpec(
            List.of(TopicSpec.of("orders")),
            List.of(new ProducerSpec(longest, List.of("orders"), 1.0)),
            List.of(group(longest, DEFAULTS, withId(longest)))
        ))).isEmpty();
        assertThat(ConfigValidator.validate(new SimulatorSpec(
            List.of(TopicSpec.of("orders")),
            List.of(new ProducerSpec(tooLong, List.of("orders"), 1.0)),
            List.of(group(tooLong, DEFAULTS, withId(tooLong)))
        )))
            .extracting(Violation::field, Violation::message).containsExactly(
                org.assertj.core.groups.Tuple.tuple("producers[0].id", "must have at most 249 characters"),
                org.assertj.core.groups.Tuple.tuple("groups[0].id", "must have at most 249 characters"),
                org.assertj.core.groups.Tuple.tuple("groups[0].consumers[0].id", "must have at most 249 characters")
            );
    }

    @Test
    void rejectsASuppliedIdMadeOfWhitespace() {
        var spec = new SimulatorSpec(
            List.of(TopicSpec.of("orders")),
            List.of(new ProducerSpec(" ", List.of("orders"), 1.0)),
            List.of(group("g", DEFAULTS, withId(" ")))
        );

        assertThat(ConfigValidator.validate(spec)).containsExactly(
            new Violation("producers[0].id", "must not be blank"),
            new Violation("groups[0].consumers[0].id", "must not be blank")
        );
    }

    @Test
    void acceptsEveryLegalCharacterInAnId() {
        var id = "Az09._-";
        var spec = new SimulatorSpec(
            List.of(TopicSpec.of(id)),
            List.of(new ProducerSpec(id, List.of(id), 1.0)),
            List.of(new GroupSpec(id, List.of(id), 45_000, 5_000, 1_000, DEFAULTS, List.of(withId(id))))
        );

        assertThat(ConfigValidator.validate(spec)).isEmpty();
    }

    @Test
    void reportsValuesMissingFromARequestBodyAsViolations() {
        var spec = new SimulatorSpec(
            List.of(new TopicSpec(null, 1)),
            List.of(new ProducerSpec(null, null, 1.0)),
            List.of(new GroupSpec(null, null, 45_000, 5_000, 1_000, null, null))
        );

        assertThat(ConfigValidator.validate(spec)).containsExactly(
            new Violation("topics[0].name", "must not be blank"),
            new Violation("producers[0].topics", "must name at least one topic"),
            new Violation("groups[0].id", "must not be blank"),
            new Violation("groups[0].subscribedTopics", "must name at least one topic")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void rejectsABlankGroupId(String id) {
        assertThat(ConfigValidator.validate(withGroup(group(id, DEFAULTS))))
            .containsExactly(new Violation("groups[0].id", "must not be blank"));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsANonPositiveOrNonFiniteRate(double rate) {
        assertThat(ConfigValidator.validateProducer(new ProducerSpec("", List.of("orders"), rate), Set.of("orders")))
            .extracting(Violation::field).containsExactly("ratePerPartition");
    }

    @Test
    void acceptsAFractionalRate() {
        assertThat(ConfigValidator.validateProducer(new ProducerSpec("", List.of("orders"), 0.1), Set.of("orders")))
            .isEmpty();
    }

    @Test
    void reportsEveryViolationAtOnce() {
        var spec = new SimulatorSpec(
            List.of(new TopicSpec("orders", 0)),
            List.of(new ProducerSpec("", List.of("missing"), 0)),
            List.of(new GroupSpec("", List.of("orders"), 1, 5_000, 1_000, DEFAULTS, List.of()))
        );

        assertThat(ConfigValidator.validate(spec)).extracting(Violation::field).containsExactly(
            "topics[0].partitions",
            "producers[0].topics[0]",
            "producers[0].ratePerPartition",
            "groups[0].id",
            "groups[0].sessionTimeoutMs"
        );
    }

    @Test
    void validatesOneGroupAgainstTheExistingTopics() {
        var group = GroupSpec.of("g", g -> g.subscribe("orders").sessionTimeout(Duration.ZERO));

        assertThat(ConfigValidator.validateGroup(group, Set.of("orders")))
            .containsExactly(new Violation("sessionTimeoutMs", "must be between 1000 and 300000, got 0"));
        assertThat(ConfigValidator.validateGroup(group, Set.of())).extracting(Violation::field)
            .containsExactly("subscribedTopics[0]", "sessionTimeoutMs");
    }

    @Test
    void validatesOneConsumerAgainstTheDefaultsOfItsGroup() {
        var consumer = overriding(Optional.of(Protocol.CLASSIC), Optional.empty());

        assertThat(ConfigValidator.validateConsumer(settings(Protocol.CONSUMER, "range"), consumer)).isEmpty();
        assertThat(ConfigValidator.validateConsumer(settings(Protocol.CONSUMER, "uniform"), consumer))
            .extracting(Violation::field).containsExactly("overrides.protocol");
    }
}
