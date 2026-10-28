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

/**
 * The state of a simulated consumer. {@link ConsumerSnapshot#status()} holds the single state a
 * frontend shows for the consumer; when several apply, the first one in the order of this enum
 * wins. {@link ConsumerSnapshot#coordinatorState()} uses the membership subset: {@link #STOPPED},
 * {@link #STOPPING}, {@link #JOINING}, {@link #RECONCILING} and {@link #STABLE}.
 */
public enum ConsumerStatus {
    /** The consumer hit an unrecoverable error and no longer runs. */
    FAILED,
    /** The consumer was asked to leave the group, or has left it. */
    STOPPED,
    /** The consumer does not poll: {@link Fault#freeze()} is active. */
    FROZEN,
    /**
     * The consumer is cut off from the cluster: {@link Fault#networkIsolation()} or
     * {@link Fault#connectionReset()} is active.
     */
    ISOLATED,
    /** The consumer was asked to leave the group and the coordinator still lists it as a member. */
    STOPPING,
    /** The consumer wants to be in the group and the coordinator does not list it as a member yet. */
    JOINING,
    /** The coordinator reports the member as not yet at its target assignment or epoch. */
    RECONCILING,
    /**
     * The coordinator reports the member at its target assignment and epoch; the client may still be
     * picking up its partitions.
     */
    STABLE
}
