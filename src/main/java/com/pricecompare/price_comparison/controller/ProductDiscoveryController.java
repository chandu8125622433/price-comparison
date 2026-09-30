package com.pricecompare.price_comparison.controller;

import com.pricecompare.price_comparison.dto.ProductDiscoveryResponse;
import com.pricecompare.price_comparison.service.ProductDiscoveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger for retailer product discovery (Phase 3 MVP).
 * Read-only: candidates are returned directly and nothing is persisted.
 */
@RestController
@RequestMapping("/api/products")
public class ProductDiscoveryController {

    private final ProductDiscoveryService discoveryService;

    public ProductDiscoveryController(ProductDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    /**
     * Discovers retailer product-page candidates for an existing product
     * (currently Vijay Sales). Per-retailer failures are reported in the
     * response instead of failing the whole request.
     */
    @GetMapping("/{productId}/discover")
    public ProductDiscoveryResponse discover(@PathVariable Long productId) {
        return discoveryService.discoverForProduct(productId);
    }
}
