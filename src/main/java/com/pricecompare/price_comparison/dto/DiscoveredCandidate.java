package com.pricecompare.price_comparison.dto;

import com.pricecompare.price_comparison.retailer.discovery.DiscoveredProduct;

/**
 * API view of one discovered retailer product candidate. Purely
 * informational in Phase 3: a candidate never becomes a persisted price on
 * its own — identity validation and the price providers decide that.
 */
public record DiscoveredCandidate(
        String retailer,
        String productTitle,
        String sku,
        String productUrl,
        String imageUrl,
        String mrp,
        String availability
) {

    public static DiscoveredCandidate from(DiscoveredProduct discovered) {
        return new DiscoveredCandidate(
                discovered.retailer().getDisplayName(),
                discovered.productTitle(),
                discovered.sku(),
                discovered.productUrl(),
                discovered.imageUrl(),
                discovered.mrp(),
                discovered.availability());
    }
}
