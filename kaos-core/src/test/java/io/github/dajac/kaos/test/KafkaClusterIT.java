package io.github.dajac.kaos.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Smoke test of the integration-test harness. */
@ExtendWith(KafkaClusterExtension.class)
class KafkaClusterIT {

    @Test
    void createsAndDescribesTopic(KafkaCluster cluster) throws Exception {
        String topic = cluster.uniqueName("smoke");

        try (Admin admin = cluster.createAdmin()) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1)))
                .all()
                .get(30, TimeUnit.SECONDS);

            TopicDescription description = admin.describeTopics(List.of(topic))
                .allTopicNames()
                .get(30, TimeUnit.SECONDS)
                .get(topic);

            assertThat(description.name()).isEqualTo(topic);
            assertThat(description.partitions()).hasSize(3);
        }
    }

    @Test
    void injectsTheSameClusterIntoEveryTest(KafkaCluster cluster, KafkaCluster other) throws Exception {
        assertThat(other).isSameAs(cluster);

        try (Admin admin = cluster.createAdmin()) {
            assertThat(admin.describeCluster().nodes().get(30, TimeUnit.SECONDS)).hasSize(1);
        }
    }
}
