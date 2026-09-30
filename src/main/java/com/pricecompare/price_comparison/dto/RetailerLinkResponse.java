package com.pricecompare.price_comparison.dto;

/**
 * Result of confirming a discovered retailer candidate.
 *
 * <p>{@code status}: {@code linked} (first confirmation), {@code updated}
 * (existing active link replaced/refreshed), or {@code already-linked}
 * (idempotent no-op). Errors use the standard error-response conventions
 * (400/404 via the global exception handler), never stack traces.</p>
 */
public record RetailerLinkResponse(
        Long productId,
        String retailer,
        String productUrl,
        String sku,
        String status,
        String message
) {
}
