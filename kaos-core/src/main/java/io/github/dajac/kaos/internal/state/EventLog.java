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
package io.github.dajac.kaos.internal.state;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.dajac.kaos.ConsumerSnapshot;
import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.GroupSnapshot;
import io.github.dajac.kaos.PartitionRef;
import io.github.dajac.kaos.SimulatorSnapshot;
import io.github.dajac.kaos.SnapshotListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The change log of the groups, on the logger {@code kaos.events}. It is a snapshot listener: every
 * line is derived from the difference between two published snapshots, so the log and the snapshots
 * have one source. {@link #diff} is a pure function.
 *
 * <p>An instance runs on the publisher thread only.
 */
public final class EventLog implements SnapshotListener {

    /** The name of the logger of the change log. */
    public static final String LOGGER_NAME = "kaos.events";

    private static final Logger EVENTS = LoggerFactory.getLogger(LOGGER_NAME);

    private List<GroupSnapshot> last = List.of();

    @Override
    public void onSnapshot(SimulatorSnapshot snapshot) {
        var groups = snapshot.groups();
        if (EVENTS.isInfoEnabled()) {
            diff(last, groups).forEach(EVENTS::info);
        }
        last = groups;
    }

    /** The lines that tell what changed in the groups between two snapshots, in a stable order. */
    public static List<String> diff(List<GroupSnapshot> before, List<GroupSnapshot> after) {
        var lines = new ArrayList<String>();
        for (var group : after) {
            var prefix = "[" + group.id() + "] ";
            var previous = find(before, GroupSnapshot::id, group.id());
            if (previous == null) {
                lines.add(prefix + "group added with " + group.consumers().size() + " consumer(s)");
                continue;
            }
            diffGroup(previous, group).forEach(line -> lines.add(prefix + line));
        }
        for (var group : before) {
            if (find(after, GroupSnapshot::id, group.id()) == null) {
                lines.add("[" + group.id() + "] group removed");
            }
        }
        return lines;
    }

    private static List<String> diffGroup(GroupSnapshot before, GroupSnapshot after) {
        var lines = new ArrayList<String>();
        if (before.type() != after.type()) {
            lines.add("group type: " + before.type() + " -> " + after.type());
        }
        if (before.state() != after.state()) {
            lines.add("group state: " + before.state() + " -> " + after.state());
        }
        if (before.epoch() != after.epoch()) {
            lines.add("group epoch: " + before.epoch() + " -> " + after.epoch());
        }
        if (before.targetAssignmentEpoch() != after.targetAssignmentEpoch()) {
            lines.add(
                "target assignment epoch: " + before.targetAssignmentEpoch() + " -> " + after.targetAssignmentEpoch()
            );
        }
        if (before.generation() != after.generation()) {
            lines.add("generation: " + before.generation() + " -> " + after.generation());
        }
        for (var consumer : after.consumers()) {
            var previous = find(before.consumers(), ConsumerSnapshot::id, consumer.id());
            if (previous == null) {
                lines.add(consumer.id() + " added");
            } else {
                diffConsumer(previous, consumer).forEach(line -> lines.add(consumer.id() + " " + line));
            }
        }
        for (var consumer : before.consumers()) {
            if (find(after.consumers(), ConsumerSnapshot::id, consumer.id()) == null) {
                lines.add(consumer.id() + " removed");
            }
        }
        return lines;
    }

    private static List<String> diffConsumer(ConsumerSnapshot before, ConsumerSnapshot after) {
        var lines = new ArrayList<String>();
        if (before.subscribed() != after.subscribed()) {
            lines.add(after.subscribed() ? "is asked to join" : "is asked to leave");
        }
        if (before.protocol() != after.protocol()) {
            lines.add("switched protocol: " + before.protocol() + " -> " + after.protocol());
        }
        for (var fault : Fault.all()) {
            var was = before.faults().contains(fault);
            var is = after.faults().contains(fault);
            if (was != is) {
                lines.add("fault " + fault.id() + (is ? " injected" : " cleared"));
            }
        }
        if (!before.memberId().equals(after.memberId())) {
            if (after.memberId().isEmpty()) {
                lines.add("left the group (member " + before.memberId() + ")");
            } else if (before.memberId().isEmpty()) {
                lines.add("joined the group (member " + after.memberId() + ")");
            } else {
                lines.add("rejoined the group (member " + before.memberId() + " -> " + after.memberId() + ")");
            }
        }
        if (before.memberEpoch() != after.memberEpoch() && after.memberEpoch() >= 0) {
            lines.add("member epoch: " + before.memberEpoch() + " -> " + after.memberEpoch());
        }
        if (before.generation() != after.generation() && after.generation() >= 0) {
            lines.add("generation: " + before.generation() + " -> " + after.generation());
        }
        if (before.status() != after.status()) {
            lines.add("status: " + before.status() + " -> " + after.status());
        }
        if (!before.failure().equals(after.failure()) && !after.failure().isEmpty()) {
            lines.add("failed: " + after.failure());
        }
        var gained = new HashSet<>(after.raw().assignment());
        before.raw().assignment().forEach(gained::remove);
        if (!gained.isEmpty()) {
            lines.add("assigned: " + format(gained));
        }
        var lost = new HashSet<>(before.raw().assignment());
        after.raw().assignment().forEach(lost::remove);
        if (!lost.isEmpty()) {
            lines.add("revoked: " + format(lost));
        }
        return lines;
    }

    private static String format(HashSet<PartitionRef> partitions) {
        return partitions.stream()
            .sorted()
            .map(partition -> partition.topic() + "-" + partition.partition())
            .collect(Collectors.joining(", "));
    }

    private static <T> T find(List<T> elements, Function<T, String> id, String wanted) {
        return elements.stream().filter(element -> Objects.equals(id.apply(element), wanted)).findFirst().orElse(null);
    }
}
