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
package io.github.dajac.kaos.internal.state;

import java.util.Optional;

import io.github.dajac.kaos.ConsumerStatus;
import io.github.dajac.kaos.Fault;
import io.github.dajac.kaos.GroupState;

/** Derives the coordinator-side state and the displayed status of one consumer. Pure functions. */
public final class StatusDeriver {
    private StatusDeriver() {}

    /**
     * The membership of a consumer as the coordinator sees it, combined with whether the consumer
     * is asked to be in the group.
     *
     * <ul>
     *   <li>Not listed by the coordinator: {@code JOINING} when subscribed, {@code STOPPED}
     *       otherwise.</li>
     *   <li>Listed and not subscribed: {@code STOPPING}.</li>
     *   <li>Listed and subscribed: {@link #memberState}.</li>
     * </ul>
     */
    public static ConsumerStatus coordinatorState(
        AdminView view,
        Optional<AdminView.Member> member,
        boolean subscribed
    ) {
        if (member.isEmpty()) {
            return subscribed ? ConsumerStatus.JOINING : ConsumerStatus.STOPPED;
        }
        return subscribed ? memberState(view, member.get()) : ConsumerStatus.STOPPING;
    }

    /**
     * Whether a listed member is {@code STABLE} or {@code RECONCILING}.
     *
     * <p>In a consumer group, a member reconciles while its current assignment differs from its
     * target, or while its epoch is behind the target assignment epoch. Any other group has no
     * per-member state, so its members reconcile while the group rebalances.
     */
    public static ConsumerStatus memberState(AdminView view, AdminView.Member member) {
        var reconciling = switch (view.type()) {
            case CONSUMER -> !member.assignment().equals(view.targetAssignmentOf(member))
                || member.memberEpoch() < view.targetAssignmentEpoch();
            case CLASSIC, UNKNOWN -> view.state() == GroupState.PREPARING_REBALANCE
                || view.state() == GroupState.COMPLETING_REBALANCE;
        };
        return reconciling ? ConsumerStatus.RECONCILING : ConsumerStatus.STABLE;
    }

    /**
     * The single status shown for a consumer. The first rule that applies wins:
     *
     * <ol>
     *   <li>{@code FAILED} when the runner failed, that is when it has a failure message.</li>
     *   <li>{@code STOPPED} when the consumer is not subscribed.</li>
     *   <li>{@code FROZEN} when {@link Fault#freeze()} is active.</li>
     *   <li>{@code ISOLATED} when {@link Fault#networkIsolation()} or
     *       {@link Fault#connectionReset()} is active.</li>
     *   <li>The coordinator state otherwise, whatever the local assignment.</li>
     * </ol>
     */
    public static ConsumerStatus status(ConsumerLocalState consumer, ConsumerStatus coordinatorState) {
        if (consumer.failed()) {
            return ConsumerStatus.FAILED;
        }
        if (!consumer.subscribed()) {
            return ConsumerStatus.STOPPED;
        }
        if (consumer.faults().contains(Fault.freeze())) {
            return ConsumerStatus.FROZEN;
        }
        if (
            consumer.faults().contains(Fault.networkIsolation())
                || consumer.faults().contains(Fault.connectionReset())
        ) {
            return ConsumerStatus.ISOLATED;
        }
        return coordinatorState;
    }
}
