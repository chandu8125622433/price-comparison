package com.pricecompare.price_comparison.dto;

import com.pricecompare.price_comparison.service.ProductSpec;
import java.util.List;

/**
 * Category listing response: a product-listing page for a category query
 * such as "mobiles", "laptops", or "tv". Contains multiple product cards
 * plus the available filters derived from the discovered products.
 *
 * <p>This is NOT a single-product comparison. Each card routes the user to
 * the per-product comparison view on "Compare prices".</p>
 */
public record CategoryListingResponse(
        String query,
        String category,
        String categoryDisplayName,
        String description,
        int totalProducts,
        List<CategoryProductCard> products,
        List<FilterGroup> filters
) {
    /**
     * A single real retailer offer behind a category card: retailer, actual
     * discovered price, availability and the product URL. Values the retailer
     * did not expose are {@code null} or empty — never fabricated.
     */
    public record CardOffer(
            String retailer,
            String price,
            String availability,
            String productUrl,
            String mrp
    ) {}

    /**
     * A single product card in the category listing. Values the retailer did
     * not expose are {@code null} or empty — never fabricated.
     */
    public record CategoryProductCard(
            Long productId,
            String name,
            String brand,
            String imageUrl,
            String lowestPrice,
            String priceRange,
            int offerCount,
            String availability,
            String productUrl,
            List<String> retailers,
            boolean verifiable,
            ProductSpec specs,
            List<CardOffer> offers
    ) {}

    /**
     * A filter group in the sidebar. Counts are derived from actual discovered
     * data; a filter with no backing data is rendered disabled/empty.
     */
    public record FilterGroup(
            String label,
            String key,
            boolean enabled,
            List<FilterOption> options
    ) {}

    public record FilterOption(
            String label,
            String value,
            int count
    ) {}
}
