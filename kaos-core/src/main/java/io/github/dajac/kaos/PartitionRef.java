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

import java.util.Comparator;
import java.util.Objects;

/**
 * A partition of a topic. The natural order is by topic name, then by partition number.
 *
 * @param topic the topic name
 * @param partition the partition number
 */
public record PartitionRef(String topic, int partition) implements Comparable<PartitionRef> {
    private static final Comparator<PartitionRef> ORDER =
        Comparator.comparing(PartitionRef::topic).thenComparingInt(PartitionRef::partition);

    public PartitionRef {
        Objects.requireNonNull(topic, "topic");
    }

    @Override
    public int compareTo(PartitionRef other) {
        return ORDER.compare(this, other);
    }

    /** The {@code topic-partition} form used by Kafka. */
    @Override
    public String toString() {
        return topic + "-" + partition;
    }
}
