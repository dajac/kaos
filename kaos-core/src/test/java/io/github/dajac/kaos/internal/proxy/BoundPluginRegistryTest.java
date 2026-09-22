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
package io.github.dajac.kaos.internal.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ServiceLoader;

import io.kroxylicious.proxy.config.ConfigParser;
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
        var factory = registry.pluginFactory(FilterFactory.class);

        assertThat(factory.pluginInstance(NetworkFaultFilterFactory.class.getName())).isSameAs(bound);
        assertThat(factory.pluginInstance(NetworkFaultFilterFactory.class.getSimpleName())).isSameAs(bound);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void delegatesTheConfigTypeAndTheNames() {
        var factory = registry.pluginFactory(FilterFactory.class);
        var delegate = parser.pluginFactory(FilterFactory.class);

        assertThat(factory.configType(NetworkFaultFilterFactory.class.getName())).isEqualTo(Void.class);
        assertThat(factory.registeredInstanceNames())
            .isEqualTo(delegate.registeredInstanceNames())
            .contains(NetworkFaultFilterFactory.class.getName());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void delegatesOtherFilterTypes() {
        var factory = registry.pluginFactory(FilterFactory.class);

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
        var unbound = ServiceLoader.load(FilterFactory.class).stream()
            .filter(provider -> provider.type() == NetworkFaultFilterFactory.class)
            .findFirst()
            .orElseThrow()
            .get();

        assertThatThrownBy(() -> ((NetworkFaultFilterFactory) unbound).initialize(null, null))
            .isInstanceOf(PluginConfigurationException.class);
    }
}
