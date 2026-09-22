package io.github.dajac.kaos.internal.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.github.dajac.kaos.Autopilot;
import io.github.dajac.kaos.ConsumerGroup;
import io.github.dajac.kaos.ConsumerHandle;
import io.github.dajac.kaos.ConsumerSpec;
import io.github.dajac.kaos.GroupSnapshot;
import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.SimulatorSnapshot;
import io.github.dajac.kaos.TopicSpec;
import io.github.dajac.kaos.internal.kafka.GroupCleanup;
import io.github.dajac.kaos.internal.kafka.GroupConfigManager;
import io.github.dajac.kaos.internal.kafka.GroupPoller;
import io.github.dajac.kaos.internal.state.AdminView;
import io.github.dajac.kaos.internal.state.GroupLocalState;
import io.github.dajac.kaos.internal.state.SnapshotAssembler;

/**
 * A consumer group of a simulator: its consumers, its autopilot and what its start leaves on the
 * cluster.
 *
 * <p>The consumers are an immutable list behind a volatile reference, replaced as a whole by
 * whoever holds the structure lock of the simulator. The publisher, the poller and the autopilot
 * read it without a lock.
 */
public final class GroupRuntime implements ConsumerGroup {

    /** What a group needs from its simulator at any time. */
    public interface Harness {
        /** Something the snapshot shows has changed. */
        void changed();

        /** Throws on a thread that must not block, such as the one that runs the listeners. */
        void refuseBlocking();

        /** The latest snapshot. */
        SimulatorSnapshot snapshot();

        /** Waits for a snapshot. */
        SimulatorSnapshot awaitSnapshot(Predicate<SimulatorSnapshot> condition, Duration timeout);
    }

    /**
     * What a group needs from its simulator to run.
     *
     * @param consumers what the consumers need
     * @param configs sets and reverts the group configs
     * @param cleanup removes the group from the cluster
     */
    public record Services(ConsumerRunner.Services consumers, GroupConfigManager configs, GroupCleanup cleanup) {
        public Services {
            Objects.requireNonNull(consumers, "consumers");
            Objects.requireNonNull(configs, "configs");
            Objects.requireNonNull(cleanup, "cleanup");
        }
    }

    private static final String ASSIGNED_ID_PREFIX = "consumer-";

    private final GroupSpec spec;
    private final List<TopicSpec> topics;
    private final Harness harness;
    private final AutopilotImpl autopilot;

    private volatile List<ConsumerRunner> consumers;
    private volatile List<String> warnings = List.of();

    // Guarded by the structure lock of the simulator.
    private int nextConsumerNumber;

    /**
     * @param spec the description of the group; its consumers without an id get one
     * @param topics the subscribed topics of the spec, with their partition counts
     * @param harness the simulator
     */
    public GroupRuntime(GroupSpec spec, List<TopicSpec> topics, Harness harness) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.topics = List.copyOf(topics);
        this.harness = Objects.requireNonNull(harness, "harness");
        this.autopilot = new AutopilotImpl(spec.id(), this::runners, harness::changed);

        // The ids that were supplied are taken first, so that no assigned id collides with one.
        var taken = spec.consumers().stream()
            .map(ConsumerSpec::id)
            .filter(id -> !id.isEmpty())
            .collect(Collectors.toCollection(HashSet::new));
        var runners = new ArrayList<ConsumerRunner>();
        for (var consumer : spec.consumers()) {
            var identified = consumer.id().isEmpty()
                ? new ConsumerSpec(nextConsumerId(taken), consumer.overrides(), consumer.effective())
                : consumer;
            runners.add(new ConsumerRunner(
                spec.id(), identified, spec.subscribedTopics(), harness::changed, harness::refuseBlocking
            ));
        }
        this.consumers = List.copyOf(runners);
    }

    /**
     * The first {@code consumer-N} that no consumer of the group has or had. The numbers only grow,
     * so an id is never used twice in the life of the group.
     */
    private String nextConsumerId(Set<String> taken) {
        while (true) {
            var id = ASSIGNED_ID_PREFIX + nextConsumerNumber++;
            if (taken.add(id)) {
                return id;
            }
        }
    }

    @Override
    public String id() {
        return spec.id();
    }

    @Override
    public List<String> subscribedTopics() {
        return spec.subscribedTopics();
    }

    @Override
    public GroupSpec describe() {
        return new GroupSpec(
            spec.id(),
            spec.subscribedTopics(),
            spec.sessionTimeoutMs(),
            spec.heartbeatIntervalMs(),
            spec.assignmentIntervalMs(),
            spec.consumerDefaults(),
            consumers.stream().map(ConsumerRunner::spec).toList()
        );
    }

    /** The runners of the consumers. */
    public List<ConsumerRunner> runners() {
        return consumers;
    }

    @Override
    public List<ConsumerHandle> consumers() {
        return List.copyOf(consumers);
    }

    @Override
    public ConsumerHandle consumer(int number) {
        return consumer(ASSIGNED_ID_PREFIX + number);
    }

    @Override
    public ConsumerHandle consumer(String id) {
        Objects.requireNonNull(id, "id");
        return consumers.stream()
            .filter(consumer -> consumer.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Group " + spec.id() + " has no consumer " + id));
    }

    @Override
    public Autopilot autopilot() {
        return autopilot;
    }

    @Override
    public List<String> warnings() {
        return warnings;
    }

    @Override
    public GroupSnapshot snapshot() {
        // A group that is not published, because it was just declared or is already removed, is
        // shown as the simulator knows it.
        return slice(harness.snapshot()).orElseGet(() -> SnapshotAssembler.assemble(localState(), AdminView.unknown()));
    }

    @Override
    public GroupSnapshot awaitSnapshot(Predicate<GroupSnapshot> condition, Duration timeout) {
        Objects.requireNonNull(condition, "condition");
        var matched = harness.awaitSnapshot(snapshot -> slice(snapshot).filter(condition).isPresent(), timeout);
        return slice(matched).orElseThrow();
    }

    private Optional<GroupSnapshot> slice(SimulatorSnapshot snapshot) {
        return snapshot.groups().stream().filter(group -> group.id().equals(spec.id())).findFirst();
    }

    /** What the poller needs to know about the group. */
    public GroupPoller.Target target() {
        return new GroupPoller.Target(spec.id(), topics);
    }

    /** The copy of the state of the group that goes into the snapshot. */
    public GroupLocalState localState() {
        return new GroupLocalState(
            spec.id(),
            topics,
            autopilot.snapshot(),
            consumers.stream().map(ConsumerRunner::localState).toList()
        );
    }

    /**
     * Sets the group configs and starts the consumers. Closing {@code resources} turns the
     * autopilot off, stops the consumers, removes the static members that are left, deletes the
     * group and reverts the group configs, in that order.
     */
    public void start(Services services, Resources resources) {
        var groupId = spec.id();

        var applied = services.configs().apply(spec);
        warnings = applied.warnings();
        resources.register("configs of group " + groupId, () -> {
            warnings = List.of();
            services.configs().revert(groupId, applied.previous());
        });
        resources.register("group " + groupId, () -> services.cleanup().deleteGroup(groupId));
        var instanceIds = consumers.stream()
            .map(ConsumerRunner::instanceId)
            .filter(instanceId -> !instanceId.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
        resources.register(
            "static members of group " + groupId,
            () -> services.cleanup().removeStaticMembers(groupId, instanceIds)
        );

        for (var consumer : consumers) {
            consumer.start(services.consumers(), resources.child("consumer " + consumer.clientId()));
        }
        // Undone first: the loops end together, and each consumer then only waits for its own.
        resources.register("consumers of group " + groupId, () -> consumers.forEach(ConsumerRunner::requestStop));

        autopilot.open(services.consumers().threads());
        resources.register("autopilot of group " + groupId, autopilot::close);
        harness.changed();
    }
}
