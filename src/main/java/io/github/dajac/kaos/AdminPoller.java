package io.github.dajac.kaos;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.common.TopicPartition;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class AdminPoller {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AdminClient admin;
    private final String groupId;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread thread;
    private volatile SimulatorGroupState lastState;

    public AdminPoller(String bootstrapServers, String groupId) {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        this.admin = AdminClient.create(props);
        this.groupId = groupId;
    }

    public SimulatorGroupState lastState() {
        return lastState;
    }

    public void start() {
        thread = new Thread(() -> {
            while (running.get()) {
                try {
                    poll();
                } catch (Exception e) {
                    if (running.get()) {
                        System.err.println("[admin-poller] Error: " + e.getMessage());
                    }
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "admin-poller");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                // Ignore.
            }
        }
        admin.close();
    }

    private void poll() throws Exception {
        ConsumerGroupDescription desc = admin.describeConsumerGroups(Collections.singleton(groupId))
            .describedGroups()
            .get(groupId)
            .get();

        SimulatorGroupState state = new SimulatorGroupState();
        state.groupEpoch = desc.groupEpoch().orElse(-1);
        state.targetAssignmentEpoch = desc.targetAssignmentEpoch().orElse(-1);
        state.groupState = desc.groupState().toString();

        List<SimulatorGroupState.MemberState> members = new ArrayList<>();
        for (MemberDescription member : desc.members()) {
            SimulatorGroupState.MemberState ms = new SimulatorGroupState.MemberState();
            ms.clientId = member.clientId();
            ms.memberId = member.consumerId();
            ms.memberEpoch = member.memberEpoch().orElse(-1);

            Set<TopicPartition> assigned = member.assignment().topicPartitions();
            Set<TopicPartition> target = member.targetAssignment()
                .map(a -> a.topicPartitions())
                .orElse(Collections.emptySet());

            ms.assignment = assigned.stream()
                .map(tp -> tp.topic() + "-" + tp.partition())
                .collect(Collectors.toSet());
            ms.targetAssignment = target.stream()
                .map(tp -> tp.topic() + "-" + tp.partition())
                .collect(Collectors.toSet());

            if (!assigned.equals(target) || ms.memberEpoch < state.targetAssignmentEpoch) {
                ms.state = "Reconciling";
            } else {
                ms.state = "Stable";
            }

            members.add(ms);
        }
        state.members = members;

        logChanges(lastState, state);
        lastState = state;
    }

    private void logChanges(SimulatorGroupState prev, SimulatorGroupState curr) {
        if (prev == null) return;

        if (!curr.groupState.equals(prev.groupState)) {
            log("Group state: %s -> %s", prev.groupState, curr.groupState);
        }
        if (curr.groupEpoch != prev.groupEpoch) {
            log("Group epoch: %d -> %d", prev.groupEpoch, curr.groupEpoch);
        }
        if (curr.targetAssignmentEpoch != prev.targetAssignmentEpoch) {
            log("Target assignment epoch: %d -> %d", prev.targetAssignmentEpoch, curr.targetAssignmentEpoch);
        }

        Map<String, SimulatorGroupState.MemberState> prevMembers = new HashMap<>();
        if (prev.members != null) {
            for (SimulatorGroupState.MemberState ms : prev.members) {
                prevMembers.put(ms.clientId, ms);
            }
        }
        Map<String, SimulatorGroupState.MemberState> currMembers = new HashMap<>();
        if (curr.members != null) {
            for (SimulatorGroupState.MemberState ms : curr.members) {
                currMembers.put(ms.clientId, ms);
            }
        }

        for (SimulatorGroupState.MemberState m : curr.members) {
            SimulatorGroupState.MemberState p = prevMembers.get(m.clientId);
            if (p == null) {
                log("%s joined (memberId: %s)", m.clientId, shorten(m.memberId));
                continue;
            }

            if (!m.state.equals(p.state)) {
                log("%s: %s -> %s", m.clientId, p.state, m.state);
            }
            if (m.memberEpoch != p.memberEpoch && m.memberEpoch >= 0) {
                log("%s: epoch %d -> %d", m.clientId, p.memberEpoch, m.memberEpoch);
            }

            Set<String> gained = new HashSet<>(m.assignment);
            gained.removeAll(p.assignment);
            Set<String> lost = new HashSet<>(p.assignment);
            lost.removeAll(m.assignment);

            if (!gained.isEmpty()) {
                log("%s assigned: %s", m.clientId, formatPartitions(gained));
            }
            if (!lost.isEmpty()) {
                log("%s revoked: %s", m.clientId, formatPartitions(lost));
            }
        }

        for (SimulatorGroupState.MemberState p : prev.members) {
            if (!currMembers.containsKey(p.clientId)) {
                log("%s left the group", p.clientId);
            }
        }
    }

    private static String shorten(String memberId) {
        if (memberId == null) return "?";
        return memberId.length() > 16 ? memberId.substring(0, 16) + "..." : memberId;
    }

    private static String formatPartitions(Set<String> partitions) {
        return partitions.stream().sorted().collect(Collectors.joining(", "));
    }

    private static void log(String format, Object... args) {
        String ts = LocalTime.now().format(TIME_FMT);
        System.out.printf("[%s] " + format + "%n", prepend(ts, args));
    }

    private static Object[] prepend(Object first, Object[] rest) {
        Object[] result = new Object[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }
}
