package io.github.dajac.kaos.internal.config;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.ConfigMetadata.Field;
import io.github.dajac.kaos.ConfigMetadata.NameRule;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks specs against {@link ConfigMetadata}. Every method is a pure function that returns all the
 * violations it finds; an empty list means the spec is valid.
 *
 * <p>The whole-spec check and the per-component checks share the same code, so a component added
 * while the simulator runs is held to the same rules as one declared up front.
 */
public final class ConfigValidator {
    private ConfigValidator() {
    }

    /** Checks a whole spec, including the references between its components. */
    public static List<Violation> validate(SimulatorSpec spec) {
        List<Violation> violations = new ArrayList<>();

        int maxTopics = ConfigMetadata.NUM_TOPICS.range().orElseThrow().max();
        if (spec.topics().size() > maxTopics) {
            violations.add(new Violation(
                "topics", "must have at most " + maxTopics + " topics, got " + spec.topics().size()));
        }

        Set<String> topicNames = new HashSet<>();
        for (int i = 0; i < spec.topics().size(); i++) {
            TopicSpec topic = spec.topics().get(i);
            String path = "topics[" + i + "]";
            checkTopic(path, topic, violations);
            if (!topicNames.add(topic.name())) {
                violations.add(duplicate(path + ".name", "topic", topic.name()));
            }
        }

        Set<String> producerIds = new HashSet<>();
        for (int i = 0; i < spec.producers().size(); i++) {
            ProducerSpec producer = spec.producers().get(i);
            String path = "producers[" + i + "]";
            checkProducer(path, producer, topicNames, violations);
            if (!producer.id().isBlank() && !producerIds.add(producer.id())) {
                violations.add(duplicate(path + ".id", "producer", producer.id()));
            }
        }

        Set<String> groupIds = new HashSet<>();
        for (int i = 0; i < spec.groups().size(); i++) {
            GroupSpec group = spec.groups().get(i);
            String path = "groups[" + i + "]";
            checkGroup(path, group, topicNames, violations);
            if (!group.id().isBlank() && !groupIds.add(group.id())) {
                violations.add(duplicate(path + ".id", "group", group.id()));
            }
        }

        return violations;
    }

    /** Checks one topic on its own. */
    public static List<Violation> validateTopic(TopicSpec topic) {
        List<Violation> violations = new ArrayList<>();
        checkTopic("", topic, violations);
        return violations;
    }

    /** Checks one producer against the names of the topics that exist. */
    public static List<Violation> validateProducer(ProducerSpec producer, Set<String> topicNames) {
        List<Violation> violations = new ArrayList<>();
        checkProducer("", producer, topicNames, violations);
        return violations;
    }

    /** Checks one group, with its consumers, against the names of the topics that exist. */
    public static List<Violation> validateGroup(GroupSpec group, Set<String> topicNames) {
        List<Violation> violations = new ArrayList<>();
        checkGroup("", group, topicNames, violations);
        return violations;
    }

    /** Checks one consumer that joins a group with the given defaults. */
    public static List<Violation> validateConsumer(ConsumerSettings consumerDefaults, ConsumerSpec consumer) {
        List<Violation> violations = new ArrayList<>();
        checkConsumer("", EffectiveConfigResolver.resolve(consumerDefaults, consumer), violations);
        return violations;
    }

    private static void checkTopic(String path, TopicSpec topic, List<Violation> violations) {
        checkName(join(path, "name"), topic.name(), violations);
        checkRange(path, ConfigMetadata.PARTITIONS, topic.partitions(), violations);
    }

    private static void checkProducer(
        String path,
        ProducerSpec producer,
        Set<String> topicNames,
        List<Violation> violations
    ) {
        if (!producer.id().isEmpty()) {
            checkName(join(path, "id"), producer.id(), violations);
        }
        checkTopicReferences(join(path, "topics"), producer.topics(), topicNames, violations);
        double rate = producer.ratePerPartition();
        if (!Double.isFinite(rate) || rate <= 0) {
            violations.add(new Violation(
                join(path, ConfigMetadata.RATE_PER_PARTITION.key()),
                "must be a finite number greater than 0, got " + rate));
        }
    }

    private static void checkGroup(
        String path,
        GroupSpec group,
        Set<String> topicNames,
        List<Violation> violations
    ) {
        checkName(join(path, "id"), group.id(), violations);
        checkTopicReferences(join(path, "subscribedTopics"), group.subscribedTopics(), topicNames, violations);
        checkRange(path, ConfigMetadata.SESSION_TIMEOUT_MS, group.sessionTimeoutMs(), violations);
        checkRange(path, ConfigMetadata.HEARTBEAT_INTERVAL_MS, group.heartbeatIntervalMs(), violations);
        checkRange(path, ConfigMetadata.ASSIGNMENT_INTERVAL_MS, group.assignmentIntervalMs(), violations);

        String defaultsPath = join(path, "consumerDefaults");
        ConsumerSettings defaults = group.consumerDefaults();
        checkRange(defaultsPath, ConfigMetadata.POLL_TIMEOUT_MS, defaults.pollTimeoutMs(), violations);
        checkRange(defaultsPath, ConfigMetadata.POLL_DELAY_MS, defaults.pollDelayMs(), violations);
        checkRange(defaultsPath, ConfigMetadata.MAX_POLL_INTERVAL_MS, defaults.maxPollIntervalMs(), violations);
        checkAssignor(defaultsPath, defaults.protocol(), defaults.assignor(), violations);

        Set<String> consumerIds = new HashSet<>();
        for (int i = 0; i < group.consumers().size(); i++) {
            ConsumerSpec consumer = group.consumers().get(i);
            String consumerPath = join(path, "consumers[" + i + "]");
            checkConsumer(consumerPath, consumer, violations);
            if (!consumer.id().isBlank() && !consumerIds.add(consumer.id())) {
                violations.add(duplicate(consumerPath + ".id", "consumer", consumer.id()));
            }
        }
    }

    /**
     * Checks the id and the overrides of a consumer whose effective settings are resolved. The
     * assignor is checked against the effective protocol, and only when the consumer overrides one
     * of the two: an inherited pair is reported once, on the defaults of the group. The violation
     * names the value the consumer set: its assignor, or else its protocol, which the inherited
     * assignor does not fit.
     */
    private static void checkConsumer(String path, ConsumerSpec consumer, List<Violation> violations) {
        if (!consumer.id().isEmpty()) {
            checkName(join(path, "id"), consumer.id(), violations);
        }
        String overridesPath = join(path, "overrides");
        ConsumerOverrides overrides = consumer.overrides();
        overrides.pollTimeoutMs().ifPresent(value ->
            checkRange(overridesPath, ConfigMetadata.POLL_TIMEOUT_MS, value, violations));
        overrides.pollDelayMs().ifPresent(value ->
            checkRange(overridesPath, ConfigMetadata.POLL_DELAY_MS, value, violations));
        overrides.maxPollIntervalMs().ifPresent(value ->
            checkRange(overridesPath, ConfigMetadata.MAX_POLL_INTERVAL_MS, value, violations));
        ConsumerSettings effective = consumer.effective();
        if (overrides.assignor().isPresent()) {
            checkAssignor(overridesPath, effective.protocol(), effective.assignor(), violations);
        } else if (overrides.protocol().isPresent()
                && !Assignors.isValid(effective.protocol(), effective.assignor())) {
            violations.add(new Violation(
                join(overridesPath, ConfigMetadata.PROTOCOL.key()),
                "the inherited assignor '" + effective.assignor() + "' is unknown for the "
                    + effective.protocol().id() + " protocol; override the assignor with one of: "
                    + knownAssignors(effective.protocol())));
        }
    }

    private static void checkAssignor(String path, Protocol protocol, String assignor, List<Violation> violations) {
        if (!Assignors.isValid(protocol, assignor)) {
            violations.add(new Violation(
                join(path, ConfigMetadata.ASSIGNOR.key()),
                "unknown assignor '" + assignor + "' for the " + protocol.id()
                    + " protocol; expected one of: " + knownAssignors(protocol)));
        }
    }

    private static String knownAssignors(Protocol protocol) {
        return String.join(", ", ConfigMetadata.get().assignors(protocol).names());
    }

    /** Checks a topic name, a group id, or a supplied producer or consumer id. */
    private static void checkName(String field, String name, List<Violation> violations) {
        NameRule rule = ConfigMetadata.get().nameRule();
        if (name.isBlank()) {
            violations.add(new Violation(field, "must not be blank"));
        } else if (name.length() > rule.maxLength()) {
            violations.add(new Violation(field, "must have at most " + rule.maxLength() + " characters"));
        } else if (!rule.accepts(name)) {
            violations.add(new Violation(
                field, "must contain only ASCII letters, digits, '.', '_' and '-', and not be '.' or '..'"));
        }
    }

    private static void checkTopicReferences(
        String path,
        List<String> references,
        Set<String> topicNames,
        List<Violation> violations
    ) {
        if (references.isEmpty()) {
            violations.add(new Violation(path, "must name at least one topic"));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < references.size(); i++) {
            String reference = references.get(i);
            if (!topicNames.contains(reference)) {
                violations.add(new Violation(path + "[" + i + "]", "unknown topic '" + reference + "'"));
            } else if (!seen.add(reference)) {
                violations.add(duplicate(path + "[" + i + "]", "topic", reference));
            }
        }
    }

    private static void checkRange(String path, Field field, int value, List<Violation> violations) {
        Range range = field.range().orElseThrow();
        if (!range.contains(value)) {
            violations.add(new Violation(
                join(path, field.key()),
                "must be between " + range.min() + " and " + range.max() + ", got " + value));
        }
    }

    private static Violation duplicate(String field, String kind, String value) {
        return new Violation(field, "duplicate " + kind + " '" + value + "'");
    }

    private static String join(String path, String field) {
        return path.isEmpty() ? field : path + "." + field;
    }
}
