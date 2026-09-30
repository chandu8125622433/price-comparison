package com.pricecompare.price_comparison.dto;

import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.service.ProductSpec;

import java.util.Comparator;
import java.util.List;

public record ProductDetailResponse(
        Long id,
        String name,
        String brand,
        String model,
        String category,
        List<PriceInfo> prices,
        String imageUrl,
        ProductSpec specs
) {

    public static ProductDetailResponse from(Product product, List<PriceInfo> prices) {
        return from(product, prices, null, null);
    }

    public static ProductDetailResponse from(Product product, List<PriceInfo> prices, String imageUrl, ProductSpec specs) {
        List<PriceInfo> sortedPrices = prices.stream()
                .sorted(Comparator.comparing(PriceInfo::price))
                .toList();

        return new ProductDetailResponse(
                product.getId(),
                product.getName(),
                product.getBrand(),
                product.getModel(),
                product.getCategory(),
                sortedPrices,
                imageUrl,
                specs
        );
    }
}