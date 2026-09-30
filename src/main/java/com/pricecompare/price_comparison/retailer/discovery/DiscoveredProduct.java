package com.pricecompare.price_comparison.retailer.discovery;

import com.pricecompare.price_comparison.retailer.RetailerKey;

import java.util.Locale;
import java.util.Objects;

/**
 * A single retailer product candidate returned by a
 * {@link ProductDiscoveryProvider}. Deliberately framework-free and
 * category-independent (TV, mobile, laptop, appliance, ...).
 *
 * <p>Discovery never persists anything: a candidate only becomes a price
 * after Phase 2 identity validation and the existing retailer price
 * providers accept it. Optional fields that the retailer does not expose
 * for a candidate are empty strings, never null.</p>
 */
public record DiscoveredProduct(
        RetailerKey retailer,
        String productUrl,
        String productTitle,
        String sku,
        String model,
        String imageUrl,
        String mrp,
        String availability,
        String price,
        String currency,
        String sourceCategory
) {

    /**
     * Back-compatible constructor for callers that do not yet carry a
     * discovered selling price or source category (Phase 3-style candidates).
     */
    public DiscoveredProduct(RetailerKey retailer, String productUrl, String productTitle,
                              String sku, String imageUrl, String mrp, String availability) {
        this(retailer, productUrl, productTitle, sku, "", imageUrl, mrp, availability, "", "", "");
    }

    /**
     * Back-compatible constructor for callers that carry a selling price
     * but not yet a source category.
     */
    public DiscoveredProduct(RetailerKey retailer, String productUrl, String productTitle,
                              String sku, String imageUrl, String mrp, String availability,
                              String price, String currency) {
        this(retailer, productUrl, productTitle, sku, "", imageUrl, mrp, availability,
                price, currency, "");
    }

    public DiscoveredProduct {
        Objects.requireNonNull(retailer, "retailer must not be null");
        if (productUrl == null || productUrl.isBlank()) {
            throw new IllegalArgumentException("productUrl must not be blank");
        }
        productUrl = productUrl.trim();
        productTitle = safe(productTitle);
        sku = safe(sku);
        model = safe(model);
        imageUrl = safe(imageUrl);
        mrp = safe(mrp);
        availability = safe(availability);
        price = safe(price);
        currency = safe(currency);
        sourceCategory = safe(sourceCategory);
    }

    /**
     * Normalized key used to remove duplicate candidates pointing at the
     * same product page (case, trailing-slash and fragment differences).
     */
    public static String dedupeKey(String productUrl) {
        String cleaned = productUrl == null ? "" : productUrl.trim();
        int fragment = cleaned.indexOf('#');
        if (fragment >= 0) {
            cleaned = cleaned.substring(0, fragment);
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
