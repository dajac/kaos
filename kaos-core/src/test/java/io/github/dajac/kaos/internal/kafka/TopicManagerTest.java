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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.dajac.kaos.SimulationException;
import io.github.dajac.kaos.TopicSpec;
import io.github.dajac.kaos.test.IntegrationTest;
import io.github.dajac.kaos.test.KafkaCluster;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@IntegrationTest
class TopicManagerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int TOPICS = 10;
    private static final int PARTITIONS = 8;

    private KafkaCluster cluster;
    private Admin admin;
    private TopicManager topics;

    @BeforeEach
    void setUp(KafkaCluster cluster) {
        this.cluster = cluster;
        this.admin = cluster.createAdmin();
        this.topics = new TopicManager(admin, TIMEOUT);
    }

    @AfterEach
    void tearDown() {
        admin.close();
    }

    @Test
    void everyPartitionAcceptsAProduceRequestOnceTheTopicIsReady() throws Exception {
        // Without retries, a produce request that reaches a leader which does not host the
        // partition yet fails with NOT_LEADER_OR_FOLLOWER instead of being sent again.
        var properties = Map.<String, Object>of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers(),
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false,
            ProducerConfig.ACKS_CONFIG, "1",
            ProducerConfig.RETRIES_CONFIG, 0
        );
        for (var i = 0; i < TOPICS; i++) {
            var name = cluster.uniqueName("ready");
            topics.create(new TopicSpec(name, PARTITIONS));
            try {
                topics.awaitReady(name);

                // A new producer, as the simulator starts one after its topics.
                try (
                    var producer = new KafkaProducer<>(properties, new StringSerializer(), new StringSerializer())
                ) {
                    var sends = new ArrayList<Future<RecordMetadata>>();
                    for (var partition = 0; partition < PARTITIONS; partition++) {
                        sends.add(producer.send(new ProducerRecord<>(name, partition, "key", "value")));
                    }
                    for (var send : sends) {
                        assertThat(send.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).offset()).isZero();
                    }
                }
            } finally {
                topics.delete(name);
            }
        }
    }

    @Test
    void aTopicThatDoesNotExistIsNeverReady() {
        var name = cluster.uniqueName("missing");
        var shortTopics = new TopicManager(admin, Duration.ofSeconds(1));

        assertThatThrownBy(() -> shortTopics.awaitReady(name))
            .isInstanceOf(SimulationException.class)
            .hasMessageContaining("is still not ready after");
    }
}
