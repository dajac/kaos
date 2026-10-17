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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.SimulationException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sets the dynamic configs of a group, and puts them back.
 *
 * <p>Only the explicit settings are sent. A setting is explicit when its value differs from its
 * default in {@link ConfigMetadata}; a setting with that default is left to the broker, even when
 * the user set it to that value. Each key is sent on its own, so a key that the
 * broker refuses does not take the others with it. The broker refuses a key it does not know, as a
 * broker before 4.3 does with {@code consumer.assignment.interval.ms}, and a value outside the
 * bounds of its own configuration. Such a key becomes a warning and the group runs without it. Any
 * other failure is thrown.
 *
 * <p>The configs are those of the consumer protocol. The session timeout and the heartbeat interval
 * apply to the members of both protocols: those that speak the classic protocol take them from
 * their client, on which {@code ConsumerRunner} sets the same explicit settings.
 */
public final class GroupConfigManager {

    /** Reads and writes the dynamic configs of groups. */
    public interface Store {
        /** The dynamic configs that are set on the group. */
        Map<String, String> read(String groupId);

        /**
         * Sets a dynamic config of the group, or deletes it when the value is empty.
         *
         * @throws RefusedConfigException if the broker refuses the key or its value
         */
        void write(String groupId, String key, Optional<String> value);
    }

    /** The broker refused a config: it does not know the key, or the value is out of its bounds. */
    public static final class RefusedConfigException extends RuntimeException {
        public RefusedConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * What {@link #apply} did.
     *
     * @param previous the keys that were set, each with the value it had before; an empty value
     *     means that the key was not set on the group
     * @param warnings one message per key that the broker refused
     */
    public record Applied(Map<String, Optional<String>> previous, List<String> warnings) {
        public Applied {
            previous = Collections.unmodifiableMap(new LinkedHashMap<>(previous));
            warnings = List.copyOf(warnings);
        }
    }

    public static final String SESSION_TIMEOUT = "consumer.session.timeout.ms";
    public static final String HEARTBEAT_INTERVAL = "consumer.heartbeat.interval.ms";
    public static final String ASSIGNMENT_INTERVAL = "consumer.assignment.interval.ms";

    private static final Logger LOG = LoggerFactory.getLogger(GroupConfigManager.class);

    private final Store store;

    public GroupConfigManager(Store store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** A manager that talks to the cluster of the admin client; every call is bounded by the timeout. */
    public GroupConfigManager(Admin admin, Duration timeout) {
        this(new AdminStore(admin, timeout));
    }

    /**
     * The group configs to set for a spec: the explicit settings, that is those whose value differs
     * from their default in {@link ConfigMetadata}, in a stable order.
     */
    public static Map<String, String> explicitConfigs(GroupSpec spec) {
        var configs = new LinkedHashMap<String, String>();
        putIfSet(configs, SESSION_TIMEOUT, ConfigMetadata.SESSION_TIMEOUT_MS, spec.sessionTimeoutMs());
        putIfSet(configs, HEARTBEAT_INTERVAL, ConfigMetadata.HEARTBEAT_INTERVAL_MS, spec.heartbeatIntervalMs());
        putIfSet(configs, ASSIGNMENT_INTERVAL, ConfigMetadata.ASSIGNMENT_INTERVAL_MS, spec.assignmentIntervalMs());
        return configs;
    }

    private static void putIfSet(Map<String, String> configs, String key, ConfigMetadata.Field field, int value) {
        if (value != field.intDefault()) {
            configs.put(key, Integer.toString(value));
        }
    }

    /** Sets the explicit configs of the group. Nothing is read or written when there is none. */
    public Applied apply(GroupSpec spec) {
        var configs = explicitConfigs(spec);
        var previous = new LinkedHashMap<String, Optional<String>>();
        var warnings = new ArrayList<String>();
        if (configs.isEmpty()) {
            return new Applied(previous, warnings);
        }
        var existing = store.read(spec.id());
        try {
            configs.forEach((key, value) -> {
                try {
                    store.write(spec.id(), key, Optional.of(value));
                    previous.put(key, Optional.ofNullable(existing.get(key)));
                } catch (RefusedConfigException e) {
                    var warning = "The broker refused the group config " + key + "=" + value + " of group "
                        + spec.id() + ", which runs without it: " + e.getMessage();
                    LOG.warn(warning);
                    warnings.add(warning);
                }
            });
        } catch (RuntimeException e) {
            // The keys that were set before the failure are put back, as nobody else will.
            try {
                revert(spec.id(), previous);
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        return new Applied(previous, warnings);
    }

    /**
     * Gives every key its previous value back, or deletes it when it had none. Every key is tried.
     *
     * @throws SimulationException if keys could not be reverted
     */
    public void revert(String groupId, Map<String, Optional<String>> previous) {
        var failures = new ArrayList<RuntimeException>();
        previous.forEach((key, value) -> {
            try {
                store.write(groupId, key, value);
            } catch (RuntimeException e) {
                failures.add(e);
            }
        });
        if (!failures.isEmpty()) {
            var failure = new SimulationException("Could not revert the configs of group " + groupId);
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    /** The store of a real cluster. */
    private record AdminStore(Admin admin, Duration timeout) implements Store {
        @Override
        public Map<String, String> read(String groupId) {
            var resource = resource(groupId);
            try {
                var config = admin.describeConfigs(List.of(resource)).all()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .get(resource);
                var dynamic = new LinkedHashMap<String, String>();
                for (var entry : config.entries()) {
                    if (entry.source() == ConfigEntry.ConfigSource.DYNAMIC_GROUP_CONFIG) {
                        dynamic.put(entry.name(), entry.value());
                    }
                }
                return dynamic;
            } catch (ExecutionException e) {
                var cause = e.getCause();
                throw new SimulationException("Could not read the configs of group " + groupId + ": " + cause, cause);
            } catch (TimeoutException e) {
                throw new SimulationException("Timed out reading the configs of group " + groupId, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SimulationException("Interrupted while reading the configs of group " + groupId, e);
            }
        }

        @Override
        public void write(String groupId, String key, Optional<String> value) {
            var op = value
                .map(set -> new AlterConfigOp(new ConfigEntry(key, set), AlterConfigOp.OpType.SET))
                .orElseGet(() -> new AlterConfigOp(new ConfigEntry(key, ""), AlterConfigOp.OpType.DELETE));
            try {
                admin.incrementalAlterConfigs(Map.of(resource(groupId), List.of(op))).all()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                var cause = e.getCause();
                if (
                    cause instanceof InvalidConfigurationException
                        || cause instanceof InvalidRequestException
                        || cause instanceof UnsupportedVersionException
                ) {
                    throw new RefusedConfigException(cause.getMessage(), cause);
                }
                throw new SimulationException("Could not set " + key + " on group " + groupId + ": " + cause, cause);
            } catch (TimeoutException e) {
                throw new SimulationException("Timed out setting " + key + " on group " + groupId, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SimulationException("Interrupted while setting " + key + " on group " + groupId, e);
            }
        }

        private static ConfigResource resource(String groupId) {
            return new ConfigResource(ConfigResource.Type.GROUP, groupId);
        }
    }
}
