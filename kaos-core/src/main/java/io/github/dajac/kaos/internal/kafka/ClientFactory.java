package io.github.dajac.kaos.internal.kafka;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Creates the Kafka clients of a simulator. Every client gets the client properties of the
 * simulator, such as the security settings. The admin client and the producers connect to the
 * cluster directly: they bypass the proxy, so no network fault reaches them.
 */
public final class ClientFactory {

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

    private Map<String, Object> properties(String clientId) {
        var properties = new HashMap<String, Object>(clientProperties);
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(CommonClientConfigs.CLIENT_ID_CONFIG, clientId);
        return properties;
    }
}
