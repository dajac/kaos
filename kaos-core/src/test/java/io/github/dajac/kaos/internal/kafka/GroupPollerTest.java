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
package io.github.dajac.kaos.internal.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.github.dajac.kaos.PartitionRef;
import io.github.dajac.kaos.TopicSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class GroupPollerTest {

    private static final List<TopicSpec> TOPICS = List.of(new TopicSpec("orders", 3));

    @Test
    void lagIsTheEndOffsetMinusTheCommittedOffset() {
        var lag = GroupPoller.lag(
            TOPICS,
            Map.of(
                new TopicPartition("orders", 0), new OffsetAndMetadata(4),
                new TopicPartition("orders", 1), new OffsetAndMetadata(10),
                new TopicPartition("orders", 2), new OffsetAndMetadata(12)
            ),
            Map.of(
                new TopicPartition("orders", 0), 10L,
                new TopicPartition("orders", 1), 10L,
                new TopicPartition("orders", 2), 10L
            )
        );

        assertThat(lag).isEqualTo(Map.of(
            new PartitionRef("orders", 0), 6L,
            new PartitionRef("orders", 1), 0L,
            new PartitionRef("orders", 2), 0L
        ));
    }

    @Test
    void aPartitionWithoutACommittedOffsetHasLagZero() {
        var lag = GroupPoller.lag(
            TOPICS,
            Map.of(new TopicPartition("orders", 0), new OffsetAndMetadata(4)),
            Map.of(
                new TopicPartition("orders", 0), 10L,
                new TopicPartition("orders", 1), 10L,
                new TopicPartition("orders", 2), 0L
            )
        );

        assertThat(lag).isEqualTo(Map.of(
            new PartitionRef("orders", 0), 6L,
            new PartitionRef("orders", 1), 0L,
            new PartitionRef("orders", 2), 0L
        ));
    }

    @Test
    void aPartitionWithoutAnEndOffsetHasAnUnknownLag() {
        var lag = GroupPoller.lag(
            TOPICS,
            Map.of(
                new TopicPartition("orders", 0), new OffsetAndMetadata(4),
                new TopicPartition("orders", 1), new OffsetAndMetadata(4)
            ),
            Map.of(new TopicPartition("orders", 0), 10L)
        );

        assertThat(lag).isEqualTo(Map.of(new PartitionRef("orders", 0), 6L));
    }
}
