package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.entity.ProductPrice;
import com.pricecompare.price_comparison.exception.ResourceNotFoundException;
import com.pricecompare.price_comparison.repository.ProductRepository;
import com.pricecompare.price_comparison.repository.ProductPriceRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class ProductPriceService {

    private final ProductRepository productRepository;
    private final ProductPriceRepository productPriceRepository;

    public ProductPriceService(
            ProductRepository productRepository,
            ProductPriceRepository productPriceRepository) {
        this.productRepository = productRepository;
        this.productPriceRepository = productPriceRepository;
    }

    public ProductPrice addPrice(
            Long productId,
            String website,
            BigDecimal price,
            String productUrl) {
        return addPrice(productId, website, price, productUrl, null);
    }

    /**
     * Upsert that also records the retailer product SKU when one is supplied.
     * A {@code null} or blank {@code sku} leaves any existing SKU untouched,
     * so a scrape that could not see a SKU never erases a confirmed one.
     */
    public ProductPrice addPrice(
            Long productId,
            String website,
            BigDecimal price,
            String productUrl,
            String sku) {

        Product product = productRepository.findById(productId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Product not found with id: " + productId));

        ProductPrice productPrice = productPriceRepository
                .findByProductIdAndWebsite(productId, website)
                .orElseGet(ProductPrice::new);

        productPrice.setProduct(product);
        productPrice.setWebsite(website);
        productPrice.setPrice(price);
        productPrice.setProductUrl(productUrl);
        productPrice.setLastUpdated(LocalDateTime.now());
        if (sku != null && !sku.isBlank()) {
            productPrice.setSku(sku.trim());
        }

        return productPriceRepository.save(productPrice);
    }
}