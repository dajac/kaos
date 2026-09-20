package io.github.dajac.kaos.internal.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ServiceLoader;

import io.kroxylicious.proxy.config.ConfigParser;
import io.kroxylicious.proxy.config.PluginFactory;
import io.kroxylicious.proxy.filter.FilterFactory;
import io.kroxylicious.proxy.plugin.PluginConfigurationException;
import org.junit.jupiter.api.Test;

class BoundPluginRegistryTest {

    private final ConfigParser parser = new ConfigParser();
    private final NetworkFaultFilterFactory bound = new NetworkFaultFilterFactory(new NetworkFaults());
    private final BoundPluginRegistry registry = new BoundPluginRegistry(parser, bound);

    @Test
    @SuppressWarnings("rawtypes")
    void returnsTheBoundInstanceForOurFilterType() {
        PluginFactory<FilterFactory> factory = registry.pluginFactory(FilterFactory.class);

        assertThat(factory.pluginInstance(NetworkFaultFilterFactory.class.getName())).isSameAs(bound);
        assertThat(factory.pluginInstance(NetworkFaultFilterFactory.class.getSimpleName())).isSameAs(bound);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void delegatesTheConfigTypeAndTheNames() {
        PluginFactory<FilterFactory> factory = registry.pluginFactory(FilterFactory.class);
        PluginFactory<FilterFactory> delegate = parser.pluginFactory(FilterFactory.class);

        assertThat(factory.configType(NetworkFaultFilterFactory.class.getName())).isEqualTo(Void.class);
        assertThat(factory.registeredInstanceNames())
            .isEqualTo(delegate.registeredInstanceNames())
            .contains(NetworkFaultFilterFactory.class.getName());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void delegatesOtherFilterTypes() {
        PluginFactory<FilterFactory> factory = registry.pluginFactory(FilterFactory.class);

        assertThatThrownBy(() -> factory.pluginInstance("NoSuchFilter"))
            .hasSameClassAs(catchDelegateFailure());
    }

    private Throwable catchDelegateFailure() {
        try {
            parser.pluginFactory(FilterFactory.class).pluginInstance("NoSuchFilter");
            throw new AssertionError("The delegate resolved an unknown filter type");
        } catch (RuntimeException e) {
            return e;
        }
    }

    @Test
    @SuppressWarnings("rawtypes")
    void theServiceLoaderFindsAnUnboundFactoryThatCannotInitialize() {
        FilterFactory unbound = ServiceLoader.load(FilterFactory.class).stream()
            .filter(provider -> provider.type() == NetworkFaultFilterFactory.class)
            .findFirst()
            .orElseThrow()
            .get();

        assertThatThrownBy(() -> ((NetworkFaultFilterFactory) unbound).initialize(null, null))
            .isInstanceOf(PluginConfigurationException.class);
    }
}
