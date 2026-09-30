package com.pricecompare.price_comparison.dto;

import com.pricecompare.price_comparison.entity.ProductPrice;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PriceInfo(
        String retailer,
        BigDecimal price,
        String productUrl,
        LocalDateTime lastUpdated,
        String status,
        boolean fresh
) {

    public static PriceInfo from(ProductPrice productPrice) {
        return new PriceInfo(
                productPrice.getWebsite(),
                productPrice.getPrice(),
                productPrice.getProductUrl(),
                productPrice.getLastUpdated(),
                "VERIFIED",
                true
        );
    }

    public PriceInfo withFresh(boolean fresh) {
        return new PriceInfo(retailer, price, productUrl, lastUpdated, status, fresh);
    }
}
