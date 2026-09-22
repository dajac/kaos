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

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.MemberToRemove;
import org.apache.kafka.clients.admin.RemoveMembersFromConsumerGroupOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes what a group leaves on the cluster once its consumers are stopped. Both steps are
 * best-effort: what they cannot do is logged and never fails the teardown, because the group may be
 * shared with members that the simulator does not own.
 */
public final class GroupCleanup {

    private static final Logger LOG = LoggerFactory.getLogger(GroupCleanup.class);

    private final Admin admin;
    private final Duration timeout;

    /**
     * @param admin the admin client; it is not closed here
     * @param timeout the bound of each admin call
     */
    public GroupCleanup(Admin admin, Duration timeout) {
        this.admin = Objects.requireNonNull(admin, "admin");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Removes the static members with the given instance ids that the coordinator still lists. A
     * consumer leaves the group when it is closed; this covers the static member that could not,
     * for example because it was isolated from the network, and would hold its partitions until
     * its session times out.
     */
    public void removeStaticMembers(String groupId, Set<String> instanceIds) throws InterruptedException {
        if (instanceIds.isEmpty()) {
            return;
        }
        try {
            var group = admin.describeConsumerGroups(List.of(groupId)).describedGroups().get(groupId)
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            var lingering = group.members().stream()
                .map(MemberDescription::groupInstanceId)
                .flatMap(Optional::stream)
                .filter(instanceIds::contains)
                .map(MemberToRemove::new)
                .toList();
            if (lingering.isEmpty()) {
                return;
            }
            var options = new RemoveMembersFromConsumerGroupOptions(lingering);
            admin.removeMembersFromConsumerGroup(groupId, options).all().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.info("Could not remove the static members of group {}: {}", groupId, String.valueOf(cause(e)));
        }
    }

    /** Deletes the group, with its committed offsets. A group that still has members is left. */
    public void deleteGroup(String groupId) throws InterruptedException {
        try {
            admin.deleteConsumerGroups(List.of(groupId)).all().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.info("Could not delete group {}: {}", groupId, String.valueOf(cause(e)));
        }
    }

    private static Throwable cause(Exception e) {
        return e instanceof ExecutionException ? e.getCause() : e;
    }
}
