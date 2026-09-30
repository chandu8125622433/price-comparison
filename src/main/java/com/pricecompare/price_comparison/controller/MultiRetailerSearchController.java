package com.pricecompare.price_comparison.controller;

import com.pricecompare.price_comparison.dto.MultiRetailerSearchResponse;
import com.pricecompare.price_comparison.service.MultiRetailerSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 7: normalized multi-retailer view for one product. Read-only — every
 * retailer's outcome is reported with an explicit status (verified price,
 * matched candidates, no match, configuration required, provider error) so
 * an unavailable retailer is never shown as if it had a real price.
 */
@RestController
@RequestMapping("/api/products")
public class MultiRetailerSearchController {

    private final MultiRetailerSearchService multiRetailerSearchService;

    public MultiRetailerSearchController(MultiRetailerSearchService multiRetailerSearchService) {
        this.multiRetailerSearchService = multiRetailerSearchService;
    }

    @GetMapping("/{productId}/multi-retailer-search")
    public MultiRetailerSearchResponse multiRetailerSearch(@PathVariable Long productId) {
        return multiRetailerSearchService.searchForProduct(productId);
    }
}
