package com.pricecompare.price_comparison.dto;

import java.util.List;
import java.util.Map;

/**
 * Result of discovering retailer product-page candidates for one product.
 * Mirrors the failure-reporting style of {@link PriceRefreshResponse}: a
 * failing retailer is reported with its reason instead of failing the whole
 * request. Nothing is persisted by discovery.
 */
public record ProductDiscoveryResponse(
        Long productId,
        String productName,
        String searchQuery,
        int providersAttempted,
        List<String> failedProviders,
        Map<String, String> failureReasons,
        List<DiscoveredCandidate> candidates
) {
}
