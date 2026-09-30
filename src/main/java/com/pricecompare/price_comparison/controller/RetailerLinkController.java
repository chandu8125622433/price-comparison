package com.pricecompare.price_comparison.controller;

import com.pricecompare.price_comparison.dto.RetailerLinkRequest;
import com.pricecompare.price_comparison.dto.RetailerLinkResponse;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.service.RetailerLinkService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 4 MVP endpoints for explicit candidate confirmation.
 * Confirmation is the ONLY way a discovered candidate becomes the active
 * retailer link that drives the existing refresh pipeline.
 */
@RestController
@RequestMapping("/api/products")
public class RetailerLinkController {

    private final RetailerLinkService retailerLinkService;

    public RetailerLinkController(RetailerLinkService retailerLinkService) {
        this.retailerLinkService = retailerLinkService;
    }

    /**
     * Confirms a discovered candidate as the product's active retailer link.
     * Invalid retailer/URL/identity candidates return controlled 4xx responses.
     */
    @PostMapping("/{productId}/retailer-links")
    public RetailerLinkResponse confirmLink(@PathVariable Long productId,
                                            @RequestBody RetailerLinkRequest request) {
        return retailerLinkService.confirmLink(productId, request);
    }

    /** Returns the product's currently active link for a retailer. */
    @GetMapping("/{productId}/retailer-links")
    public RetailerLinkResponse getLink(@PathVariable Long productId) {
        return retailerLinkService.getLink(productId, RetailerKey.VIJAY_SALES);
    }
}
