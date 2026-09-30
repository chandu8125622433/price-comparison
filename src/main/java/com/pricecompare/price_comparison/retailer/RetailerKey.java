package com.pricecompare.price_comparison.retailer;

import java.net.URI;
import java.util.Locale;

/**
 * Canonical identifiers for supported retailers.
 * These stable keys are the single source of truth and will be used
 * as the link between provider abstractions and persisted price rows.
 */
public enum RetailerKey {

    VIJAY_SALES("Vijay Sales"),
    AMAZON("Amazon"),
    FLIPKART("Flipkart"),
    CROMA("Croma"),
    RELIANCE_DIGITAL("Reliance Digital");

    private final String displayName;

    RetailerKey(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Legacy seed rows have retailer labels but example.com URLs. They must
     * remain usable by old fixtures while being ineligible for a live offer.
     */
    public static boolean hasOfficialProductUrl(String retailer, String url) {
        if (retailer == null || url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String name = retailer.trim().toLowerCase(Locale.ROOT);
            return switch (name) {
                case "vijay sales" -> host.equals("www.vijaysales.com") || host.equals("vijaysales.com");
                case "reliance digital" -> host.equals("www.reliancedigital.in") || host.equals("reliancedigital.in");
                case "flipkart" -> host.equals("www.flipkart.com") || host.equals("flipkart.com");
                case "amazon", "amazon india" -> host.equals("www.amazon.in") || host.equals("amazon.in");
                case "croma" -> host.equals("www.croma.com") || host.equals("croma.com");
                default -> false;
            };
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
