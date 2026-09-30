package com.pricecompare.price_comparison.retailer.discovery;

import com.pricecompare.price_comparison.retailer.RetailerKey;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of all {@link ProductDiscoveryProvider} beans discovered by
 * Spring. Mirrors {@link com.pricecompare.price_comparison.retailer.RetailerRegistry}
 * so discovery integrations plug in the same way price providers do,
 * without coupling the discovery service to concrete retailers.
 */
@Component
public class ProductDiscoveryRegistry {

    private final Map<RetailerKey, ProductDiscoveryProvider> providersByKey;

    public ProductDiscoveryRegistry(List<ProductDiscoveryProvider> providers) {
        Map<RetailerKey, ProductDiscoveryProvider> map = new EnumMap<>(RetailerKey.class);
        for (ProductDiscoveryProvider provider : providers) {
            map.putIfAbsent(provider.retailer(), provider);
        }
        // UnmodifiableEnumMap (NOT Map.copyOf): EnumMap iterates in enum
        // declaration order, so provider execution order is deterministic
        // across JVM runs. Map.copyOf would order by identity hash, which
        // is unstable and made multi-provider discovery nondeterministic.
        this.providersByKey = Collections.unmodifiableMap(map);
    }

    public Optional<ProductDiscoveryProvider> findByKey(RetailerKey key) {
        return Optional.ofNullable(providersByKey.get(key));
    }

    public List<ProductDiscoveryProvider> allProviders() {
        return List.copyOf(providersByKey.values());
    }

    public List<RetailerKey> registeredKeys() {
        return providersByKey.keySet().stream().sorted().toList();
    }
}
