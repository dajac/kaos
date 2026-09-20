package io.github.dajac.kaos.test;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit extension that provides a {@link KafkaCluster} to integration tests.
 *
 * <p>A test class registers it through {@link IntegrationTest} and declares
 * a {@link KafkaCluster} parameter on its constructor, on a lifecycle method
 * or on a test method:
 *
 * <pre>{@code
 * @IntegrationTest
 * class ExampleTest {
 *     @Test
 *     void example(KafkaCluster cluster) {
 *         String bootstrapServers = cluster.bootstrapServers();
 *     }
 * }
 * }</pre>
 *
 * <p>The broker starts when the first parameter is resolved. It is kept in
 * the root store of the JUnit engine, so all the test classes of a JVM share
 * one broker, and JUnit closes it when the engine finishes. The extension
 * holds no static state.
 */
public final class KafkaClusterExtension implements ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(KafkaClusterExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == KafkaCluster.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return extensionContext.getRoot()
            .getStore(NAMESPACE)
            .computeIfAbsent(KafkaCluster.class, key -> KafkaCluster.start(), KafkaCluster.class);
    }
}
