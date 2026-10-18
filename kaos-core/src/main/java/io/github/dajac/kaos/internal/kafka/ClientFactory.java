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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Creates the Kafka clients of a simulator. Every client gets the client properties of the
 * simulator, such as the security settings. The admin client and the producers connect to the
 * cluster directly: they bypass the proxy, so no network fault reaches them. The consumers connect
 * to the bootstrap servers they are given, which are those of the proxy.
 */
public final class ClientFactory {

    /**
     * The {@code request.timeout.ms} of a consumer, unless the client properties set another. A
     * consumer whose request the proxy dropped recovers when that request times out, so the value
     * is shorter than the default of the client.
     */
    static final int CONSUMER_REQUEST_TIMEOUT_MS = 10_000;

    private final String bootstrapServers;
    private final Map<String, String> clientProperties;

    /**
     * @param bootstrapServers the cluster
     * @param clientProperties the properties given to every client
     */
    public ClientFactory(String bootstrapServers, Map<String, String> clientProperties) {
        this.bootstrapServers = Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        this.clientProperties = Map.copyOf(clientProperties);
    }

    /** The properties given to every client. */
    public Map<String, String> clientProperties() {
        return clientProperties;
    }

    /** Creates an admin client. The caller closes it. */
    public Admin createAdmin(String clientId) {
        return Admin.create(properties(clientId));
    }

    /** Creates a producer of string records. The caller closes it. */
    public Producer<String, String> createProducer(String clientId) {
        var properties = properties(clientId);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return new KafkaProducer<>(properties);
    }

    /**
     * Creates a consumer of string records. The caller closes it.
     *
     * @param bootstrapServers the servers the consumer connects to, instead of the cluster
     * @param settings the settings of this consumer, the client id included; they win over the
     *     client properties of the simulator
     */
    public Consumer<String, String> createConsumer(String bootstrapServers, Map<String, Object> settings) {
        return new KafkaConsumer<>(consumerProperties(bootstrapServers, settings));
    }

    Map<String, Object> consumerProperties(String bootstrapServers, Map<String, Object> settings) {
        var properties = new HashMap<String, Object>();
        properties.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, CONSUMER_REQUEST_TIMEOUT_MS);
        properties.putAll(clientProperties);
        properties.putAll(settings);
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return properties;
    }

    private Map<String, Object> properties(String clientId) {
        var properties = new HashMap<String, Object>(clientProperties);
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(CommonClientConfigs.CLIENT_ID_CONFIG, clientId);
        return properties;
    }
}
