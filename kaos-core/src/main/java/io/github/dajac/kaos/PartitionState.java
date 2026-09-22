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

/** The state of a partition from the point of view of one consumer. */
public enum PartitionState {
    /** The consumer owns the partition and the coordinator wants it to. */
    ASSIGNED,
    /** The coordinator wants the consumer to own the partition and the consumer does not yet. */
    PENDING,
    /** The consumer still owns the partition and the coordinator wants it to give it up. */
    REVOKING,
    /** The partition has nothing to do with the consumer. */
    EMPTY
}
