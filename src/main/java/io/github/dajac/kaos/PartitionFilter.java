package io.github.dajac.kaos;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;

import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import io.kroxylicious.proxy.filter.Filter;
import io.kroxylicious.proxy.filter.FilterContext;
import io.kroxylicious.proxy.filter.FilterFactory;
import io.kroxylicious.proxy.filter.FilterFactoryContext;
import io.kroxylicious.proxy.filter.RequestFilter;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.plugin.Plugin;

@Plugin(configType = Void.class)
public class PartitionFilter implements FilterFactory<Void, Void> {

    private static final Set<String> BLOCKED_CLIENT_IDS = ConcurrentHashMap.newKeySet();

    public static void block(String clientId) {
        BLOCKED_CLIENT_IDS.add(clientId);
    }

    public static void unblock(String clientId) {
        BLOCKED_CLIENT_IDS.remove(clientId);
    }

    public static boolean isBlocked(String clientId) {
        return BLOCKED_CLIENT_IDS.contains(clientId);
    }

    public static void clear() {
        BLOCKED_CLIENT_IDS.clear();
    }

    @Override
    public Void initialize(FilterFactoryContext context, Void config) {
        return null;
    }

    @Override
    public Filter createFilter(FilterFactoryContext context, Void initData) {
        return new BlockingRequestFilter();
    }

    private static class BlockingRequestFilter implements RequestFilter {
        @Override
        public CompletionStage<RequestFilterResult> onRequest(
                ApiKeys apiKey,
                short apiVersion,
                RequestHeaderData header,
                ApiMessage request,
                FilterContext context) {
            if (header.clientId() != null && BLOCKED_CLIENT_IDS.contains(header.clientId())) {
                return context.requestFilterResultBuilder().drop().completed();
            }
            return context.forwardRequest(header, request);
        }
    }
}
