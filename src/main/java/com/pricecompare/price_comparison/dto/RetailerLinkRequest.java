package com.pricecompare.price_comparison.dto;

/**
 * Request body for confirming a discovered retailer candidate.
 * {@code productTitle} is optional but recommended: when supplied, the
 * backend immediately applies the Phase 2 identity guard to the candidate
 * instead of deferring entirely to refresh time.
 */
public record RetailerLinkRequest(
        String retailer,
        String productUrl,
        String sku,
        String productTitle
) {
}
