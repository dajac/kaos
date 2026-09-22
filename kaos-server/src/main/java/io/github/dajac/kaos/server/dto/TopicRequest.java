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
package io.github.dajac.kaos.server.dto;

import java.util.Objects;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.TopicSpec;

/**
 * A topic in a request body. A missing {@code partitions} takes the default of
 * {@link ConfigMetadata#PARTITIONS}.
 *
 * @param name the topic name
 * @param partitions the number of partitions, or null for the default
 */
public record TopicRequest(String name, Integer partitions) {

    /** The spec this request describes. */
    public TopicSpec toSpec() {
        return new TopicSpec(name, Objects.requireNonNullElse(partitions, ConfigMetadata.PARTITIONS.intDefault()));
    }
}
