package com.pricecompare.price_comparison.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Multi-retailer search response (Phase 7): every registered retailer's
 * normalized outcome for one product, plus the lowest VERIFIED current price
 * across retailers and its difference to the highest verified price. Offers
 * from different variants are never compared — only identity-verified rows.
 */
public record MultiRetailerSearchResponse(
        Long productId,
        String productName,
        String searchQuery,
        List<RetailerOfferSummary> retailers,
        LowestVerifiedPrice lowestVerifiedPrice
) {

    /**
     * The lowest verified current price across retailers. Only persisted,
     * identity-validated retailer prices participate; {@code null} when no
     * retailer has a verified price yet.
     */
    public record LowestVerifiedPrice(
            String retailer,
            BigDecimal price,
            String productUrl,
            java.time.LocalDateTime lastUpdated,
            BigDecimal differenceToHighestVerified
    ) {
    }
}
