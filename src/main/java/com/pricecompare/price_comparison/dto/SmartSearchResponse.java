package com.pricecompare.price_comparison.dto;

import java.util.List;

/**
 * Cache-first search response (Phase "cache-first product search").
 *
 * <p>{@code decision} tells the UI what actually happened so it can react
 * without re-guessing:</p>
 *
 * <ul>
 *   <li>{@code CACHE_HIT_FRESH} — the product was in PostgreSQL and its live
 *       retailer offers are within the freshness window; returned immediately,
 *       no retailer discovery/scraping happened.</li>
 *   <li>{@code CACHE_HIT_STALE} — the product was in PostgreSQL but its offers
 *       are older than the freshness window; the UI refreshes prices through
 *       the existing refresh endpoint and reloads.</li>
 *   <li>{@code DB_MISS_DISCOVERED} — the product was NOT in PostgreSQL, so
 *       live retailer discovery ran, a strict identity match was found and the
 *       confirmed product/offer snapshot was persisted.</li>
 *   <li>{@code DB_MISS_NO_MATCH} — no exact (strict) retailer match was found;
 *       a controlled message is returned instead of a bare "no products".</li>
 *   <li>{@code EMPTY_QUERY} — input validation.</li>
 * </ul>
 *
 * <p>This is read/compute-mostly: persistence happens only on the
 * {@code DB_MISS_DISCOVERED} path (confirmed product + verified offer).</p>
 */
public record SmartSearchResponse(
        String query,
        String decision,
        String message,
        List<ProductDetailResponse> products,
        CategoryListingResponse categoryListing
) {

    /** Convenience factory for the cache-first search decision (specific product). */
    public static SmartSearchResponse of(String decision, String message,
                                         List<ProductDetailResponse> products) {
        return new SmartSearchResponse("", decision, message, products, null);
    }

    /** Convenience factory for category search results. */
    public static SmartSearchResponse ofCategory(String decision, String message,
                                                  CategoryListingResponse listing) {
        return new SmartSearchResponse("", decision, message, List.of(), listing);
    }
}