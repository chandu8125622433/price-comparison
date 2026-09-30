package com.pricecompare.price_comparison.dto;

import com.pricecompare.price_comparison.retailer.discovery.DiscoveredProduct;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One normalized retailer offer for a product in the multi-retailer search.
 *
 * <p>{@code verified=true} offers come exclusively from the existing
 * refresh pipeline (a confirmed retailer link plus an identity-validated,
 * persisted {@code ProductPrice}). {@code verified=false} offers are
 * identity-matched discovery candidates that carry no persisted price yet —
 * they are candidates, never live prices.</p>
 */
public record RetailerOffer(
        String retailer,
        String sku,
        String title,
        String price,
        String mrp,
        String currency,
        String availability,
        String productUrl,
        String imageUrl,
        LocalDateTime lastUpdated,
        boolean verified
) {

    public static RetailerOffer fromVerified(com.pricecompare.price_comparison.entity.ProductPrice row,
                                             String retailerDisplayName) {
        return new RetailerOffer(
                retailerDisplayName,
                row.getSku() == null ? "" : row.getSku(),
                "",
                row.getPrice() == null ? "" : row.getPrice().toPlainString(),
                "",
                "INR",
                "",
                row.getProductUrl() == null ? "" : row.getProductUrl(),
                "",
                row.getLastUpdated(),
                true);
    }

    public static RetailerOffer fromCandidate(DiscoveredProduct candidate) {
        return new RetailerOffer(
                candidate.retailer().getDisplayName(),
                candidate.sku(),
                candidate.productTitle(),
                candidate.price(),
                candidate.mrp(),
                candidate.currency(),
                candidate.availability(),
                candidate.productUrl(),
                candidate.imageUrl(),
                null,
                false);
    }
}
