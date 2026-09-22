/*
 * Copyright 2026 The Kaos Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dajac.kaos;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The table of everything configurable: the fields with their defaults and ranges, the assignors of
 * each protocol, and the faults. It is the single source of truth: the spec defaults, the validation
 * and the {@code /meta} endpoint all read it.
 *
 * @param fields the configurable fields
 * @param nameRule the rule for topic names, group ids, and the producer and consumer ids that are
 *     supplied rather than assigned
 * @param assignors the assignors of each protocol
 * @param faults the identifiers of the faults, see {@link Fault#id()}
 */
public record ConfigMetadata(
    List<Field> fields,
    NameRule nameRule,
    Map<Protocol, AssignorOptions> assignors,
    List<String> faults
) {
    /** The number of topics a frontend creates by default. The maximum applies to every spec. */
    public static final Field NUM_TOPICS =
        Field.ofInt("numTopics", "Topics", Scope.SIMULATOR, 1, 1, 20);

    /** {@link TopicSpec#partitions()}. */
    public static final Field PARTITIONS =
        Field.ofInt("partitions", "Partitions", Scope.TOPIC, 36, 1, 100);

    /** {@link ProducerSpec#ratePerPartition()}: one record per partition every 500 ms. */
    public static final Field RATE_PER_PARTITION = new Field(
        "ratePerPartition", "Rate per Partition (records/s)", Scope.PRODUCER, Type.DOUBLE, 2.0, Optional.empty()
    );

    /** {@link GroupSpec#id()}. */
    public static final Field GROUP_ID =
        new Field("id", "Group ID", Scope.GROUP, Type.STRING, "kaos", Optional.empty());

    /** The number of consumers a frontend puts in a new group by default. */
    public static final Field NUM_CONSUMERS =
        new Field("numConsumers", "Consumers", Scope.GROUP, Type.INT, 4, Optional.empty());

    /** {@link GroupSpec#sessionTimeoutMs()}. */
    public static final Field SESSION_TIMEOUT_MS =
        Field.ofInt("sessionTimeoutMs", "Session Timeout (ms)", Scope.GROUP, 45_000, 1_000, 300_000);

    /** {@link GroupSpec#heartbeatIntervalMs()}. */
    public static final Field HEARTBEAT_INTERVAL_MS =
        Field.ofInt("heartbeatIntervalMs", "Heartbeat Interval (ms)", Scope.GROUP, 5_000, 500, 60_000);

    /** {@link GroupSpec#assignmentIntervalMs()}. */
    public static final Field ASSIGNMENT_INTERVAL_MS =
        Field.ofInt("assignmentIntervalMs", "Assignment Interval (ms)", Scope.GROUP, 1_000, 0, 60_000);

    /** {@link ConsumerSettings#protocol()}. */
    public static final Field PROTOCOL =
        new Field("protocol", "Protocol", Scope.CONSUMER, Type.PROTOCOL, Protocol.CONSUMER, Optional.empty());

    /** {@link ConsumerSettings#assignor()}. The empty name selects the default of the protocol. */
    public static final Field ASSIGNOR =
        new Field("assignor", "Assignor", Scope.CONSUMER, Type.STRING, "", Optional.empty());

    /** {@link ConsumerSettings#staticMember()}. */
    public static final Field STATIC_MEMBER =
        new Field("staticMember", "Static Membership", Scope.CONSUMER, Type.BOOLEAN, false, Optional.empty());

    /** {@link ConsumerSettings#rackId()}. The empty id means no rack. */
    public static final Field RACK_ID =
        new Field("rackId", "Rack ID", Scope.CONSUMER, Type.STRING, "", Optional.empty());

    /** {@link ConsumerSettings#pollTimeoutMs()}. */
    public static final Field POLL_TIMEOUT_MS =
        Field.ofInt("pollTimeoutMs", "Poll Timeout (ms)", Scope.CONSUMER, 0, 0, 60_000);

    /** {@link ConsumerSettings#pollDelayMs()}. */
    public static final Field POLL_DELAY_MS =
        Field.ofInt("pollDelayMs", "Poll Delay (ms)", Scope.CONSUMER, 1_000, 0, 120_000);

    /** {@link ConsumerSettings#maxPollIntervalMs()}. */
    public static final Field MAX_POLL_INTERVAL_MS =
        Field.ofInt("maxPollIntervalMs", "Max Poll Interval (ms)", Scope.CONSUMER, 300_000, 1_000, 600_000);

    /**
     * The rule of Kafka's legal topic names. Group ids and supplied producer and consumer ids follow
     * it too, because they end up in client ids and in the URL paths of the HTTP API.
     */
    public static final NameRule NAME_RULE = new NameRule("[a-zA-Z0-9._-]+", 249);

    private static final ConfigMetadata INSTANCE = new ConfigMetadata(
        List.of(
            NUM_TOPICS,
            PARTITIONS,
            RATE_PER_PARTITION,
            GROUP_ID,
            NUM_CONSUMERS,
            SESSION_TIMEOUT_MS,
            HEARTBEAT_INTERVAL_MS,
            ASSIGNMENT_INTERVAL_MS,
            PROTOCOL,
            ASSIGNOR,
            STATIC_MEMBER,
            RACK_ID,
            POLL_TIMEOUT_MS,
            POLL_DELAY_MS,
            MAX_POLL_INTERVAL_MS
        ),
        NAME_RULE,
        Map.of(
            Protocol.CONSUMER, new AssignorOptions(List.of("uniform", "range"), true),
            Protocol.CLASSIC,
            new AssignorOptions(List.of("range", "roundrobin", "sticky", "cooperative-sticky"), false)
        ),
        Fault.all().stream().map(Fault::id).toList()
    );

    public ConfigMetadata {
        fields = List.copyOf(fields);
        Objects.requireNonNull(nameRule, "nameRule");
        assignors = Map.copyOf(assignors);
        faults = List.copyOf(faults);
    }

    /** The metadata of this version of the simulator. */
    public static ConfigMetadata get() {
        return INSTANCE;
    }

    /** The field with the given scope and key, if any. Keys are unique within a scope. */
    public Optional<Field> field(Scope scope, String key) {
        return fields.stream()
            .filter(field -> field.scope() == scope && field.key().equals(key))
            .findFirst();
    }

    /** The assignors of the given protocol. */
    public AssignorOptions assignors(Protocol protocol) {
        return assignors.get(Objects.requireNonNull(protocol, "protocol"));
    }

    /** What a field belongs to. */
    public enum Scope { SIMULATOR, TOPIC, PRODUCER, GROUP, CONSUMER }

    /** The type of the value of a field. */
    public enum Type { INT, DOUBLE, BOOLEAN, STRING, PROTOCOL }

    /**
     * An inclusive range of integers.
     *
     * @param min the smallest accepted value
     * @param max the largest accepted value
     */
    public record Range(int min, int max) {
        public Range {
            if (min > max) {
                throw new IllegalArgumentException("min " + min + " is greater than max " + max);
            }
        }

        /** Whether the range includes the value. */
        public boolean contains(int value) {
            return value >= min && value <= max;
        }
    }

    /**
     * The rule a name or an id follows: it matches {@code pattern}, has at most {@code maxLength}
     * characters, and is neither {@code .} nor {@code ..}.
     *
     * @param pattern the regular expression the whole name matches
     * @param maxLength the largest accepted length
     */
    public record NameRule(String pattern, int maxLength) {
        public NameRule {
            Objects.requireNonNull(pattern, "pattern");
        }

        /** Whether the name follows the rule. */
        public boolean accepts(String name) {
            return name.length() <= maxLength
                && !name.equals(".")
                && !name.equals("..")
                && name.matches(pattern);
        }
    }

    /**
     * One configurable field.
     *
     * @param key the name of the field in the specs and in the violations
     * @param label a human-readable name
     * @param scope what the field belongs to
     * @param type the type of the value
     * @param defaultValue the value used when none is given; its class matches {@code type}
     * @param range the accepted values, for the bounded {@link Type#INT} fields
     */
    public record Field(
        String key,
        String label,
        Scope scope,
        Type type,
        Object defaultValue,
        Optional<Range> range
    ) {
        public Field {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(defaultValue, "defaultValue");
            Objects.requireNonNull(range, "range");
        }

        static Field ofInt(String key, String label, Scope scope, int defaultValue, int min, int max) {
            return new Field(key, label, scope, Type.INT, defaultValue, Optional.of(new Range(min, max)));
        }

        /** The default of an {@link Type#INT} field. */
        public int intDefault() {
            return (Integer) defaultValue;
        }

        /** The default of a {@link Type#DOUBLE} field. */
        public double doubleDefault() {
            return (Double) defaultValue;
        }

        /** The default of a {@link Type#BOOLEAN} field. */
        public boolean booleanDefault() {
            return (Boolean) defaultValue;
        }

        /** The default of a {@link Type#STRING} field. */
        public String stringDefault() {
            return (String) defaultValue;
        }

        /** The default of a {@link Type#PROTOCOL} field. */
        public Protocol protocolDefault() {
            return (Protocol) defaultValue;
        }
    }

    /**
     * The assignors of one protocol.
     *
     * @param names the known assignor names
     * @param customAllowed whether a name outside {@code names} is accepted
     */
    public record AssignorOptions(List<String> names, boolean customAllowed) {
        public AssignorOptions {
            names = List.copyOf(names);
        }

        /**
         * Whether the name can be used with the protocol. The empty name is always accepted: it
         * selects the default assignor of the protocol.
         */
        public boolean accepts(String name) {
            return name.isEmpty() || customAllowed || names.contains(name);
        }
    }
}
