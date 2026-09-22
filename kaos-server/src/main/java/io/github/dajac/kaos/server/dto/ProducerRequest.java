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

import java.util.List;
import java.util.Objects;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.ProducerSpec;

/**
 * A producer in a request body. A missing {@code ratePerPartition} takes the default of
 * {@link ConfigMetadata#RATE_PER_PARTITION}.
 *
 * @param id the producer id, or null or empty to have one assigned
 * @param topics the topics the producer writes to
 * @param ratePerPartition the records per second and partition, or null for the default
 */
public record ProducerRequest(String id, List<String> topics, Double ratePerPartition) {

    /** The spec this request describes. */
    public ProducerSpec toSpec() {
        return new ProducerSpec(
            id, topics, Objects.requireNonNullElse(ratePerPartition, ConfigMetadata.RATE_PER_PARTITION.doubleDefault())
        );
    }
}
