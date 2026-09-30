package com.pricecompare.price_comparison.controller;

import com.pricecompare.price_comparison.dto.CategoryListingResponse;
import com.pricecompare.price_comparison.dto.ProductDetailResponse;
import com.pricecompare.price_comparison.dto.SearchSuggestion;
import com.pricecompare.price_comparison.dto.SmartSearchResponse;
import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.repository.ProductRepository;
import com.pricecompare.price_comparison.service.ProductService;
import com.pricecompare.price_comparison.service.SmartSearchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository productRepository;
    private final ProductService productService;
    private final SmartSearchService smartSearchService;

    public ProductController(ProductRepository productRepository,
                             ProductService productService,
                             SmartSearchService smartSearchService) {
        this.productRepository = productRepository;
        this.productService = productService;
        this.smartSearchService = smartSearchService;
    }

    @GetMapping
    public List<ProductDetailResponse> getAllProducts() {
        return productRepository.findAll()
                .stream()
                .map(product ->
                        productService.getProductDetail(product.getId()))
                .toList();
    }

    @GetMapping("/search")
    public List<ProductDetailResponse> searchProducts(@RequestParam String q) {
        return productService.searchProducts(q)
                .stream()
                .map(product ->
                        productService.getProductDetail(product.getId()))
                .toList();
    }

/**
     * Cache-first search: PostgreSQL hit + fresh offers -> immediate stored
     * result (no retailer calls); PostgreSQL hit + stale offers -> result with
     * a "refresh needed" decision; PostgreSQL miss -> live retailer discovery,
     * strict identity confirmation, persistence of the confirmed product +
     * verified offer snapshot.
     *
     * Supports pagination via page (1-based) and pageSize (max 100) parameters
     * for category results.
     */
    @GetMapping("/smart-search")
    public SmartSearchResponse smartSearch(
            @RequestParam String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int pageSize) {
        return smartSearchService.search(q, page, pageSize);
    }

    /**
     * Category product-listing search. For a category query such as "mobiles",
     * discovers multiple relevant products from retailer discovery providers,
     * filters out non-products (warranty, accessories), de-duplicates across
     * retailers, and returns a product-listing page — never a single arbitrary
     * PostgreSQL row.
     *
     * Supports pagination via page (1-based) and pageSize (max 100) parameters.
     */
    @GetMapping("/category-search")
    public CategoryListingResponse categorySearch(
            @RequestParam String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int pageSize,
            @RequestParam(required = false) List<String> brand,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "price-asc") String sort,
            @RequestParam(required = false) List<String> filters) {
        Map<String, List<String>> specFilters = parseSpecFilters(filters);
        return smartSearchService.categorySearch(q, page, pageSize, brand, minPrice, maxPrice, sort, specFilters);
    }

    private static Map<String, List<String>> parseSpecFilters(List<String> filters) {
        if (filters == null || filters.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String filter : filters) {
            int colonIdx = filter.indexOf(':');
            if (colonIdx > 0) {
                String key = filter.substring(0, colonIdx).trim();
                String value = filter.substring(colonIdx + 1).trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    result.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                }
            }
        }
        return result;
    }

    /**
     * Search-autocomplete suggestions. Returns category and brand+category
     * suggestions for a partial query so the user can pick a scoped search.
     */
    @GetMapping("/search-suggestions")
    public SearchSuggestion searchSuggestions(@RequestParam String q) {
        return smartSearchService.suggestions(q);
    }

    @GetMapping("/{productId}")
    public ProductDetailResponse getProductById(@PathVariable Long productId) {
        return productService.getProductDetail(productId);
    }

    @PostMapping
    public ResponseEntity<Product> createProduct(@RequestBody Product product) {
        Product saved = productRepository.save(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }
}
