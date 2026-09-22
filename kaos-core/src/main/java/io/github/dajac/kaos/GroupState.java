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

/** The state of a consumer group, as the group coordinator reports it. */
public enum GroupState {
    /** The coordinator has not reported the group yet. */
    UNKNOWN,
    /** The group has no members. */
    EMPTY,
    /** A consumer group computes a new target assignment. */
    ASSIGNING,
    /** A consumer group has members that have not reached the target assignment yet. */
    RECONCILING,
    /** Every member has its assignment. */
    STABLE,
    /** A classic group waits for its members to rejoin. */
    PREPARING_REBALANCE,
    /** A classic group waits for the assignment of its leader. */
    COMPLETING_REBALANCE,
    /** The group is being removed. */
    DEAD
}
