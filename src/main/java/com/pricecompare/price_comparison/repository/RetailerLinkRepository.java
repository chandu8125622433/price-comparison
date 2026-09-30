package com.pricecompare.price_comparison.repository;

import com.pricecompare.price_comparison.entity.RetailerLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Data access for the active confirmed retailer link of a Product.
 * The unique (product_id, website) constraint guarantees at most one
 * active link per retailer, so {@code findByProductIdAndWebsite} is the
 * single lookup used by both linking and the refresh pipeline.
 */
public interface RetailerLinkRepository extends JpaRepository<RetailerLink, Long> {

    Optional<RetailerLink> findByProductIdAndWebsite(Long productId, String website);

    /** All confirmed retailer links of a product (at most one per retailer). */
    List<RetailerLink> findByProductId(Long productId);

    /** Find retailer link by product URL and retailer website. */
    Optional<RetailerLink> findByProductUrlAndWebsite(String productUrl, String website);

    /** Batch fetch retailer links by product URLs. */
    List<RetailerLink> findByProductUrlIn(Set<String> productUrls);
}
