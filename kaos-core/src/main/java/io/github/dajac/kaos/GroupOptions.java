package io.github.dajac.kaos;

import io.github.dajac.kaos.internal.config.EffectiveConfigResolver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The options of a consumer group, set from a lambda. The order of the calls does not matter: the
 * consumers inherit from the final {@code consumerDefaults}.
 */
public final class GroupOptions {
    private final List<String> subscribedTopics = new ArrayList<>();
    private int sessionTimeoutMs = ConfigMetadata.SESSION_TIMEOUT_MS.intDefault();
    private int heartbeatIntervalMs = ConfigMetadata.HEARTBEAT_INTERVAL_MS.intDefault();
    private int assignmentIntervalMs = ConfigMetadata.ASSIGNMENT_INTERVAL_MS.intDefault();
    private ConsumerOverrides consumerDefaults = ConsumerOverrides.none();
    private final List<ConsumerSpec> consumers = new ArrayList<>();

    GroupOptions() {
    }

    /** Runs the lambda on fresh options and returns the spec it describes. */
    static GroupSpec spec(String id, Consumer<GroupOptions> options) {
        Objects.requireNonNull(options, "options");
        GroupOptions target = new GroupOptions();
        options.accept(target);
        return new GroupSpec(
            id,
            target.subscribedTopics,
            target.sessionTimeoutMs,
            target.heartbeatIntervalMs,
            target.assignmentIntervalMs,
            EffectiveConfigResolver.resolve(ConsumerSettings.defaults(), target.consumerDefaults),
            target.consumers);
    }

    /** Adds topics the consumers subscribe to, by name. */
    public GroupOptions subscribe(String... topics) {
        for (String topic : topics) {
            subscribedTopics.add(Objects.requireNonNull(topic, "topic"));
        }
        return this;
    }

    /** The session timeout of the group. */
    public GroupOptions sessionTimeout(Duration sessionTimeout) {
        this.sessionTimeoutMs = Durations.toMillis(sessionTimeout);
        return this;
    }

    /** The heartbeat interval of the group. */
    public GroupOptions heartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatIntervalMs = Durations.toMillis(heartbeatInterval);
        return this;
    }

    /** The assignment interval of the group. */
    public GroupOptions assignmentInterval(Duration assignmentInterval) {
        this.assignmentIntervalMs = Durations.toMillis(assignmentInterval);
        return this;
    }

    /**
     * The settings the consumers inherit. What the lambda does not set keeps the default of
     * {@link ConfigMetadata}. A later call replaces an earlier one.
     */
    public GroupOptions consumerDefaults(Consumer<ConsumerOptions> defaults) {
        this.consumerDefaults = ConsumerOptions.overrides(defaults);
        return this;
    }

    /** Adds one consumer with its own overrides. */
    public GroupOptions consumer(Consumer<ConsumerOptions> overrides) {
        consumers.add(ConsumerSpec.of(overrides));
        return this;
    }

    /** Adds {@code count} consumers that inherit every setting. */
    public GroupOptions consumers(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative, got " + count);
        }
        for (int i = 0; i < count; i++) {
            consumers.add(ConsumerSpec.inheriting());
        }
        return this;
    }
}
