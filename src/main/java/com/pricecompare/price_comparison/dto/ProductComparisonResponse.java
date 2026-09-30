package com.pricecompare.price_comparison.dto;

import com.pricecompare.price_comparison.entity.Product;

import java.util.Comparator;
import java.util.List;

public record ProductComparisonResponse(
        Long productId,
        String name,
        String brand,
        String model,
        String category,
        List<PriceInfo> retailerPrices,
        PriceInfo lowestPriceRetailer
) {

    public static ProductComparisonResponse from(Product product, List<PriceInfo> prices) {
        List<PriceInfo> sortedPrices = prices.stream()
                .sorted(Comparator.comparing(PriceInfo::price))
                .toList();

        PriceInfo lowest = sortedPrices.isEmpty() ? null : sortedPrices.get(0);

        return new ProductComparisonResponse(
                product.getId(),
                product.getName(),
                product.getBrand(),
                product.getModel(),
                product.getCategory(),
                sortedPrices,
                lowest
        );
    }
}