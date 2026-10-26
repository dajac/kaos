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
package io.github.dajac.kaos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import io.github.dajac.kaos.GroupSpec;
import io.github.dajac.kaos.Phase;
import io.github.dajac.kaos.Simulator;
import io.github.dajac.kaos.SimulatorSpec;
import io.github.dajac.kaos.TopicSpec;
import io.github.dajac.kaos.test.IntegrationTest;
import io.github.dajac.kaos.test.KafkaCluster;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The operations of the lifecycle thread: their ordering against a stop, and their failures. */
@IntegrationTest
class SimulationServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private KafkaCluster cluster;
    private Admin admin;
    private Simulator simulator;
    private SimulationService service;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp(KafkaCluster cluster) {
        this.cluster = cluster;
        this.admin = cluster.createAdmin();
        this.simulator = new Simulator(cluster.bootstrapServers()).withProxyPort(cluster.proxyPort());
        this.service = new SimulationService(simulator);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        service.close();
        admin.close();
    }

    @Test
    void aDynamicOperationQueuedBehindAStopDoesNotRun() throws Exception {
        startWithOneTopic();
        blockTheLifecycleThread();
        var extra = cluster.uniqueName("extra");
        service.addTopic(new TopicSpec(extra, 1));

        // The simulator stops before the queued add begins.
        simulator.stop();
        release.countDown();

        await().atMost(TIMEOUT).untilAsserted(
            () -> assertThat(service.status().lastError()).contains("Add topic " + extra, "not running")
        );
        assertThat(service.status().phase()).isEqualTo(Phase.IDLE);
        assertThat(simulator.describe().topics()).isEmpty();
        assertThat(admin.listTopics().names().get()).doesNotContain(extra);
    }

    @Test
    void aDynamicOperationIsRefusedOnceAStopIsAccepted() {
        startWithOneTopic();
        blockTheLifecycleThread();

        assertThat(service.stop()).isTrue();
        assertThatThrownBy(() -> service.addTopic(new TopicSpec(cluster.uniqueName("extra"), 1)))
            .isInstanceOf(ApiException.class)
            .hasMessage("The simulator is not running")
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(409));

        release.countDown();
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(service.status().phase()).isEqualTo(Phase.IDLE));
        assertThat(simulator.describe().topics()).isEmpty();
    }

    @Test
    void aStartWithAGroupConfigTheBrokerRefusesEndsIdleWithTheError() {
        var topic = cluster.uniqueName("orders");
        var groupId = cluster.uniqueName("refused");

        service.start(
            new SimulatorSpec(List.of(new TopicSpec(topic, 1)), List.of(), List.of(refusedGroup(groupId, topic))));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(service.status().phase()).isEqualTo(Phase.IDLE);
            assertThat(service.status().lastError()).contains(REFUSED + groupId);
        });
        assertThat(simulator.describe().groups()).isEmpty();
    }

    @Test
    void anAddWithAGroupConfigTheBrokerRefusesStaysRunningWithTheError() {
        startWithOneTopic();
        var topic = simulator.describe().topics().get(0).name();
        var groupId = cluster.uniqueName("refused");

        service.addGroup(refusedGroup(groupId, topic));

        await().atMost(TIMEOUT)
            .untilAsserted(() -> assertThat(service.status().lastError()).contains(REFUSED + groupId));
        assertThat(service.status().phase()).isEqualTo(Phase.RUNNING);
        assertThat(simulator.describe().groups()).isEmpty();
    }

    /** The start of the message of the refusal of {@link #refusedGroup}, before the group id. */
    private static final String REFUSED = "The broker refused the group configs consumer.session.timeout.ms=30000, "
        + "consumer.heartbeat.interval.ms=20000 of group ";

    /** A group whose heartbeat interval is over the group.consumer.max.heartbeat.interval.ms of the broker. */
    private static GroupSpec refusedGroup(String groupId, String topic) {
        return GroupSpec.of(groupId, g -> g
            .subscribe(topic)
            .sessionTimeout(Duration.ofSeconds(30))
            .heartbeatInterval(Duration.ofSeconds(20))
            .consumers(1)
        );
    }

    private void startWithOneTopic() {
        service.start(new SimulatorSpec(List.of(new TopicSpec(cluster.uniqueName("orders"), 1)), List.of(), List.of()));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(service.status().phase()).isEqualTo(Phase.RUNNING));
    }

    /** Keeps the lifecycle thread busy until {@link #release} opens, so later operations queue. */
    private void blockTheLifecycleThread() {
        service.submit("Block", () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
