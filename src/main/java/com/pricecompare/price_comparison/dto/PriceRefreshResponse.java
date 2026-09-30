package com.pricecompare.price_comparison.dto;

import java.util.List;
import java.util.Map;

/**
 * Concise result of a manual retailer price refresh.
 * Failure reasons are reported verbatim so blockers (CAPTCHA walls,
 * missing price elements, unreachable hosts) are visible to the caller
 * instead of being masked as success.
 */
public record PriceRefreshResponse(
        Long productId,
        int providersAttempted,
        List<String> successfulProviders,
        List<String> failedProviders,
        int pricesSaved,
        Map<String, String> failureReasons
) {
}
