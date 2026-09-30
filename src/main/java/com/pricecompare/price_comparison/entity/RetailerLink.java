package com.pricecompare.price_comparison.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * The ACTIVE confirmed retailer product link for a Product (Phase 4).
 *
 * <p>Exactly one row per (product, retailer): confirming a new candidate
 * replaces the previous link instead of accumulating rows. Discovery stays
 * read-only — only an explicit user confirmation ever writes here, and the
 * existing price refresh prefers this URL (falling back to price rows).
 * No price history is stored on the link; {@link ProductPrice} keeps its
 * existing upsert semantics.</p>
 */
@Entity
@Table(name = "retailer_links",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_retailer_link_product_website",
                columnNames = {"product_id", "website"}))
public class RetailerLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Retailer display name ({@code RetailerKey.getDisplayName()}), e.g. "Vijay Sales". */
    @Column(nullable = false, length = 64)
    private String website;

    /** Validated public product page URL of the confirmed candidate. */
    @Column(name = "product_url", nullable = false, length = 1024)
    private String productUrl;

    /** Retailer product SKU from the confirmed candidate; nullable. */
    @Column(length = 64)
    private String sku;

    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    public RetailerLink() {
    }

    public RetailerLink(Product product, String website, String productUrl, String sku) {
        this.product = product;
        this.website = website;
        this.productUrl = productUrl;
        setSku(sku);
        this.linkedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
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

    public String getProductUrl() {
        return productUrl;
    }

    public void setProductUrl(String productUrl) {
        this.productUrl = productUrl;
    }

    public String getSku() {
        return sku;
    }

    /** Blank/null-safe: a link never stores an empty-string SKU. */
    public void setSku(String sku) {
        this.sku = sku == null || sku.isBlank() ? null : sku.trim();
    }

    public LocalDateTime getLinkedAt() {
        return linkedAt;
    }

    public void setLinkedAt(LocalDateTime linkedAt) {
        this.linkedAt = linkedAt;
    }
}
