package com.pricecompare.price_comparison.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_prices")
public class ProductPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private String website;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(name = "product_url", nullable = false)
    private String productUrl;

    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    /**
     * Retailer product SKU captured from the confirmed retailer link or the
     * last successful scrape. Nullable: legacy/demo rows have no SKU.
     */
    @Column(name = "sku", length = 64)
    private String sku;

    public ProductPrice() {
    }

    public ProductPrice(Product product, String website, BigDecimal price, String productUrl, LocalDateTime lastUpdated) {
        this(product, website, price, productUrl, lastUpdated, null);
    }

    public ProductPrice(Product product, String website, BigDecimal price, String productUrl,
                        LocalDateTime lastUpdated, String sku) {
        this.product = product;
        this.website = website;
        this.price = price;
        this.productUrl = productUrl;
        this.lastUpdated = lastUpdated;
        this.sku = sku == null ? null : sku.trim();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getProductUrl() {
        return productUrl;
    }

    public void setProductUrl(String productUrl) {
        this.productUrl = productUrl;
    }

    public LocalDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(LocalDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku == null ? null : sku.trim();
    }
}
