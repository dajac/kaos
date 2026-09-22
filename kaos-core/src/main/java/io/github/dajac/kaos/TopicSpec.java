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

import java.util.Objects;

/**
 * The description of a topic created and owned by the simulator. A {@code null} name is read as
 * empty, which the validation rejects.
 *
 * @param name the topic name
 * @param partitions the number of partitions
 */
public record TopicSpec(String name, int partitions) {
    public TopicSpec {
        name = Objects.requireNonNullElse(name, "");
    }

    /** A topic with the default number of partitions. */
    public static TopicSpec of(String name) {
        return new TopicSpec(name, ConfigMetadata.PARTITIONS.intDefault());
    }
}
