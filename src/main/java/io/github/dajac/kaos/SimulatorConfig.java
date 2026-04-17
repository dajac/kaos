package io.github.dajac.kaos;

import java.util.ArrayList;
import java.util.List;

public class SimulatorConfig {
    // Setup.
    public String groupId;
    public String topicPrefix;
    public int numTopics;
    public int numPartitions;
    public int numConsumers;

    // Group.
    public int sessionTimeoutMs;
    public int heartbeatIntervalMs;
    public int assignmentIntervalMs;

    // Consumer defaults (used when no per-consumer config is provided).
    public String protocol; // "consumer" or "classic"
    public String assignor;
    public int pollTimeoutMs;
    public int pollDelayMs;
    public int maxPollIntervalMs;
    public boolean staticMembers;

    // Per-consumer configs (optional — overrides the defaults above).
    public List<ConsumerConfig> consumers;

    public static class ConsumerConfig {
        public String protocol;
        public String assignor;
        public int pollTimeoutMs;
        public int pollDelayMs;
        public int maxPollIntervalMs;
        public boolean staticMember;
    }

    public SimulatorConfig() {
        this.groupId = "kaos";
        this.topicPrefix = "kaos";
        this.numTopics = 1;
        this.numPartitions = 36;
        this.numConsumers = 4;
        this.sessionTimeoutMs = 45000;
        this.heartbeatIntervalMs = 5000;
        this.assignmentIntervalMs = 1000;
        this.protocol = "consumer";
        this.assignor = "";
        this.pollTimeoutMs = 0;
        this.pollDelayMs = 1000;
        this.maxPollIntervalMs = 300000;
        this.staticMembers = false;
    }

    public List<String> topicNames() {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= numTopics; i++) {
            names.add(topicPrefix + "-" + i);
        }
        return names;
    }
}
