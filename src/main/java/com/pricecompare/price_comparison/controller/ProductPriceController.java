package com.pricecompare.price_comparison.controller;

import com.pricecompare.price_comparison.dto.PriceInfo;
import com.pricecompare.price_comparison.dto.ProductComparisonResponse;
import com.pricecompare.price_comparison.entity.ProductPrice;
import com.pricecompare.price_comparison.repository.ProductPriceRepository;
import com.pricecompare.price_comparison.service.ProductPriceService;
import com.pricecompare.price_comparison.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductPriceController {

    private final ProductPriceRepository productPriceRepository;
    private final ProductPriceService productPriceService;
    private final ProductService productService;

    public ProductPriceController(ProductPriceRepository productPriceRepository,
                                  ProductPriceService productPriceService,
                                  ProductService productService) {
        this.productPriceRepository = productPriceRepository;
        this.productPriceService = productPriceService;
        this.productService = productService;
    }

    @GetMapping("/{productId}/prices")
    public ResponseEntity<List<PriceInfo>> getProductPrices(@PathVariable Long productId) {
        productService.requireProduct(productId);

        List<PriceInfo> prices = productPriceRepository.findByProductIdOrderByPriceAsc(productId)
                .stream()
                .map(PriceInfo::from)
                .toList();

        return ResponseEntity.ok(prices);
    }

    @GetMapping("/{productId}/compare")
    public ProductComparisonResponse compareProductPrices(@PathVariable Long productId) {
        return productService.getProductComparison(productId);
    }

    @PostMapping("/{productId}/prices")
    public ResponseEntity<ProductPrice> addProductPrice(
            @PathVariable Long productId,
            @RequestBody PriceRequest request) {

        ProductPrice savedPrice = productPriceService.addPrice(
                productId,
                request.website(),
                request.price(),
                request.productUrl()
        );

        return ResponseEntity.ok(savedPrice);
    }

    public record PriceRequest(
            String website,
            BigDecimal price,
            String productUrl
    ) {
    }
}