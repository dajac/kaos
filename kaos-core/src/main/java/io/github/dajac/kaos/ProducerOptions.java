package io.github.dajac.kaos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** The options of a producer, set from a lambda. */
public final class ProducerOptions {
    private String id = "";
    private final List<String> topics = new ArrayList<>();
    private double ratePerPartition = ConfigMetadata.RATE_PER_PARTITION.doubleDefault();

    ProducerOptions() {}

    /** Runs the lambda on fresh options and returns the spec it describes. */
    static ProducerSpec spec(Consumer<ProducerOptions> options) {
        Objects.requireNonNull(options, "options");
        var target = new ProducerOptions();
        options.accept(target);
        return new ProducerSpec(target.id, target.topics, target.ratePerPartition);
    }

    /** The producer id. The simulator assigns one when it is not set. */
    public ProducerOptions id(String id) {
        this.id = Objects.requireNonNull(id, "id");
        return this;
    }

    /** Adds topics to write to, by name. */
    public ProducerOptions topics(String... topics) {
        for (var topic : topics) {
            this.topics.add(Objects.requireNonNull(topic, "topic"));
        }
        return this;
    }

    /** Adds topics to write to. */
    public ProducerOptions topics(Topic... topics) {
        for (var topic : topics) {
            this.topics.add(Objects.requireNonNull(topic, "topic").name());
        }
        return this;
    }

    /** The records written per second to each partition. */
    public ProducerOptions ratePerPartition(double recordsPerSecond) {
        this.ratePerPartition = recordsPerSecond;
        return this;
    }
}
