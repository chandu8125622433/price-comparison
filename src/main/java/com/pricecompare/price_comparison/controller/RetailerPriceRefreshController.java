package com.pricecompare.price_comparison.controller;

import com.pricecompare.price_comparison.dto.PriceRefreshResponse;
import com.pricecompare.price_comparison.service.RetailerPriceRefreshService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger for refreshing prices from all registered real-retailer
 * providers. Intentionally simple for testing/demo purposes — scheduling
 * and background scraping are intentionally out of scope for now.
 */
@RestController
@RequestMapping("/api/products")
public class RetailerPriceRefreshController {

    private final RetailerPriceRefreshService refreshService;

    public RetailerPriceRefreshController(RetailerPriceRefreshService refreshService) {
        this.refreshService = refreshService;
    }

    /**
     * Calls every registered RetailerPriceProvider for the product and
     * persists valid prices. Per-provider failures are reported in the
     * response (with reasons) instead of failing the whole request.
     */
    @PostMapping("/{productId}/refresh-prices")
    public PriceRefreshResponse refreshPrices(@PathVariable Long productId) {
        return refreshService.refreshPricesForProduct(productId);
    }
}