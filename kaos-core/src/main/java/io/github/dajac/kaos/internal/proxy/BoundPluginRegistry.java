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

import java.util.Objects;
import java.util.Set;

import io.kroxylicious.proxy.config.PluginFactory;
import io.kroxylicious.proxy.config.PluginFactoryRegistry;
import io.kroxylicious.proxy.filter.FilterFactory;

/**
 * A plugin registry that resolves the {@link NetworkFaultFilterFactory} filter type to one bound
 * instance, and delegates every other lookup.
 *
 * <p>The proxy normally instantiates its filter factories through the ServiceLoader, which only
 * knows the no-arg constructor. Each proxy gets its own registry instead, so its filter chain
 * uses the factory bound to its own {@link NetworkFaults}.
 */
public final class BoundPluginRegistry implements PluginFactoryRegistry {

    private final PluginFactoryRegistry delegate;
    private final NetworkFaultFilterFactory boundFactory;

    public BoundPluginRegistry(PluginFactoryRegistry delegate, NetworkFaultFilterFactory boundFactory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.boundFactory = Objects.requireNonNull(boundFactory, "boundFactory");
    }

    @Override
    public <P> PluginFactory<P> pluginFactory(Class<P> pluginClass) {
        var delegateFactory = delegate.pluginFactory(pluginClass);
        if (pluginClass != FilterFactory.class) {
            return delegateFactory;
        }
        return new PluginFactory<>() {
            @Override
            public P pluginInstance(String instanceName) {
                if (isBoundType(instanceName)) {
                    return pluginClass.cast(boundFactory);
                }
                return delegateFactory.pluginInstance(instanceName);
            }

            @Override
            public Class<?> configType(String instanceName) {
                return delegateFactory.configType(instanceName);
            }

            @Override
            public Set<String> registeredInstanceNames() {
                return delegateFactory.registeredInstanceNames();
            }
        };
    }

    /** A filter definition names its type by the fully qualified or the simple class name. */
    private static boolean isBoundType(String instanceName) {
        return NetworkFaultFilterFactory.class.getName().equals(instanceName)
            || NetworkFaultFilterFactory.class.getSimpleName().equals(instanceName);
    }
}
