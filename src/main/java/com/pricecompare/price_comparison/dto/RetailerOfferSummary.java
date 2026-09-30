package com.pricecompare.price_comparison.dto;

import java.util.List;

/**
 * Per-retailer result of the multi-retailer search. {@code status} clearly
 * distinguishes retailer outcomes so an unavailable retailer can never be
 * mistaken for one with a real price:
 *
 * <ul>
 *   <li>{@code VERIFIED_PRICE} — a confirmed link plus an identity-validated
 *       persisted price for this retailer;</li>
 *   <li>{@code MATCHED_CANDIDATES} — discovery returned identity-matching
 *       candidates, but no verified price exists yet;</li>
 *   <li>{@code NO_MATCHING_PRODUCT} — candidates were found but none matches
 *       the product's identity;</li>
 *   <li>{@code CONFIGURATION_REQUIRED} — the retailer's API credentials are
 *       missing (e.g. Flipkart before configuration);</li>
 *   <li>{@code PROVIDER_ERROR} — the retailer failed (network, blocked,
 *       unparsable) without affecting other retailers.</li>
 * </ul>
 */
public record RetailerOfferSummary(
        String retailer,
        String status,
        String message,
        List<RetailerOffer> offers
) {
}
