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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Holds the Java API example of the README, so that the build compiles it, and checks that the
 * README shows the same code: a change of one goes with a change of the other. The example needs a
 * broker on {@code localhost:9092}, so it is compiled and not run.
 */
class ReadmeExampleTest {

    private static final Path README = Path.of("..", "README.md");
    private static final Path SOURCE = Path.of("src", "test", "java", "io", "github", "dajac", "kaos",
        "ReadmeExampleTest.java");

    // The lines between the markers are the Java block of the README, without this indentation.
    @SuppressWarnings("unused")
    private static void example() {
        // README:begin
        try (var simulator = new Simulator("localhost:9092")) {
            Topic orders = simulator.addTopic("orders", 12);
            simulator.addProducer(p -> p.topics(orders).ratePerPartition(2));

            ConsumerGroup group = simulator.addGroup("orders-app", g -> g
                .subscribe(orders)
                .consumerDefaults(c -> c.protocol(Protocol.CONSUMER).assignor("uniform"))
                .consumers(3) // consumer-0..2 inherit the defaults
                .consumer(c -> c.protocol(Protocol.CLASSIC).assignor("range"))); // consumer-3 overrides them

            simulator.start(); // creates the topics, starts the proxy, the producer and the consumers

            // Wait until every consumer is a stable member of the group.
            group.awaitSnapshot(
                s -> s.consumers().stream().allMatch(c -> c.status() == ConsumerStatus.STABLE),
                Duration.ofMinutes(1));

            // Cut consumer-0 off the network: the proxy drops its requests until the fault is cleared.
            ConsumerHandle c0 = group.consumer(0);
            c0.inject(Fault.networkIsolation());
            group.awaitSnapshot(s -> s.consumers().get(0).partitions().isEmpty(), Duration.ofMinutes(2));
            c0.clear(Fault.networkIsolation());

            // Upgrade consumer-3 to the consumer protocol while the group runs.
            group.consumer(3).switchProtocol(Protocol.CONSUMER);

            GroupSnapshot snapshot = group.snapshot();
            System.out.println(snapshot.state() + " at epoch " + snapshot.epoch() + ", lag " + snapshot.lag());
        } // close() stops everything and deletes the topics and the group

        // README:end
    }

    @Test
    void theReadmeShowsTheExample() throws IOException {
        assertThat(javaBlocks(Files.readString(README))).containsExactly(normalize(exampleSource()));
    }

    private static String exampleSource() throws IOException {
        var source = Files.readString(SOURCE);
        var begin = source.indexOf("// README:begin\n") + "// README:begin\n".length();
        var end = source.indexOf("// README:end", begin);
        return source.substring(begin, source.lastIndexOf('\n', end) + 1);
    }

    private static List<String> javaBlocks(String markdown) {
        return Pattern.compile("```java\n(.*?)```", Pattern.DOTALL)
            .matcher(markdown)
            .results()
            .map(match -> normalize(match.group(1)))
            .toList();
    }

    /** Removes the common indentation, the trailing blanks of each line and the trailing empty lines. */
    private static String normalize(String code) {
        var lines = code.lines().map(String::stripTrailing).toList();
        var indent = lines.stream()
            .filter(line -> !line.isEmpty())
            .mapToInt(line -> line.length() - line.stripLeading().length())
            .min()
            .orElse(0);
        return String.join("\n", lines.stream().map(line -> line.isEmpty() ? line : line.substring(indent)).toList())
            .stripTrailing();
    }
}
