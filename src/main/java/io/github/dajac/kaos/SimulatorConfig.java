package io.github.dajac.kaos;

import java.util.ArrayList;
import java.util.List;

public class SimulatorConfig {
    public String topicPrefix;
    public int numTopics;
    public int numPartitions;
    public String assignor;
    public int numConsumers;
    public String groupId;
    public int defaultProcessingDelayMs;

    public SimulatorConfig() {
        this.topicPrefix = "simulator-events";
        this.numTopics = 2;
        this.numPartitions = 12;
        this.assignor = "";
        this.numConsumers = 6;
        this.groupId = "simulator-group";
        this.defaultProcessingDelayMs = 10000;
    }

    public List<String> topicNames() {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= numTopics; i++) {
            names.add(topicPrefix + "-" + i);
        }
        return names;
    }
}
