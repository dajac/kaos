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

/** The type of a consumer group, as the group coordinator reports it. */
public enum GroupType {
    /** The coordinator has not reported the group yet, or reports a type a simulated group never has. */
    UNKNOWN,
    /** A group of the consumer rebalance protocol of KIP-848. It can hold classic members too. */
    CONSUMER,
    /** A group of the classic rebalance protocol. */
    CLASSIC
}
