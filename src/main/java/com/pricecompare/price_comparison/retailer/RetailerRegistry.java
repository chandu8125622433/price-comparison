package com.pricecompare.price_comparison.retailer;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of all {@link RetailerPriceProvider} beans discovered by Spring.
 * Enables callers to resolve a provider by canonical {@link RetailerKey}
 * without coupling core services to concrete retailer implementations.
 */
@Component
public class RetailerRegistry {

    private final Map<RetailerKey, RetailerPriceProvider> providersByKey;

    public RetailerRegistry(List<RetailerPriceProvider> providers) {
        Map<RetailerKey, RetailerPriceProvider> map = new EnumMap<>(RetailerKey.class);
        for (RetailerPriceProvider provider : providers) {
            map.putIfAbsent(provider.retailer(), provider);
        }
        this.providersByKey = Map.copyOf(map);
    }

    public Optional<RetailerPriceProvider> findByKey(RetailerKey key) {
        return Optional.ofNullable(providersByKey.get(key));
    }

    public List<RetailerPriceProvider> allProviders() {
        return List.copyOf(providersByKey.values());
    }

    public List<RetailerKey> registeredKeys() {
        return providersByKey.keySet().stream().sorted().toList();
    }
}