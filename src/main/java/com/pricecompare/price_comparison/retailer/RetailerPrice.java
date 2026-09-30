package com.pricecompare.price_comparison.retailer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Normalized price data produced by a {@link RetailerPriceProvider}.
 * This is the ONLY representation that crosses the retailer boundary:
 * raw HTML, JSON, WebDriver, and WebElement objects NEVER leak past providers.
 */
public record RetailerPrice(
        RetailerKey retailer,
        BigDecimal price,
        String productUrl,
        String productTitle,
        LocalDateTime fetchedAt,
        String sku,
        String model
) {
    /**
     * Canonical constructor that normalizes null SKU and model to empty
     * strings so callers never receive a null identity token.
     */
    public RetailerPrice {
        sku = sku == null ? "" : sku.trim();
        model = model == null ? "" : model.trim();
    }

    /**
     * Back-compatible constructor for callers that do not carry a model.
     */
    public RetailerPrice(RetailerKey retailer, BigDecimal price, String productUrl,
                          String productTitle, LocalDateTime fetchedAt, String sku) {
        this(retailer, price, productUrl, productTitle, fetchedAt, sku, "");
    }
}