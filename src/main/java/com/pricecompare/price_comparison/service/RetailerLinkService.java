package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.dto.RetailerLinkRequest;
import com.pricecompare.price_comparison.dto.RetailerLinkResponse;
import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.entity.RetailerLink;
import com.pricecompare.price_comparison.exception.ResourceNotFoundException;
import com.pricecompare.price_comparison.repository.RetailerLinkRepository;
import com.pricecompare.price_comparison.retailer.ProductIdentityMatcher;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.retailer.RetailerPrice;
import com.pricecompare.price_comparison.retailer.discovery.VijaySalesDiscoveryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Phase 4 MVP: explicit candidate confirmation (retailer linking).
 *
 * <p>A discovered candidate NEVER becomes active on its own. Confirmation
 * here is the only write path, and it re-applies the Phase 2
 * {@link ProductIdentityMatcher} to the candidate before anything is
 * stored — a valid-looking URL alone is not enough.</p>
 *
 * <p>At most one active link per (product, retailer) exists: confirming a
 * new candidate replaces the previous link (the latest confirmed URL/SKU is
 * what the existing refresh pipeline uses); re-confirming the identical
 * candidate is an idempotent no-op. Nothing is deleted from existing price
 * data.</p>
 */
@Service
public class RetailerLinkService {

    private static final Logger log = LoggerFactory.getLogger(RetailerLinkService.class);

    private final ProductService productService;
    private final RetailerLinkRepository retailerLinkRepository;
    private final ProductIdentityMatcher identityMatcher;

    public RetailerLinkService(ProductService productService,
                               RetailerLinkRepository retailerLinkRepository,
                               ProductIdentityMatcher identityMatcher) {
        this.productService = productService;
        this.retailerLinkRepository = retailerLinkRepository;
        this.identityMatcher = identityMatcher;
    }

    @Transactional
    public RetailerLinkResponse confirmLink(Long productId, RetailerLinkRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        Product product = productService.requireProduct(productId);
        RetailerKey retailer = parseRetailer(request.retailer());
        String productUrl = request.productUrl() == null ? "" : request.productUrl().trim();
        if (productUrl.isEmpty()) {
            throw new IllegalArgumentException("Product URL must not be empty.");
        }
        validateProductUrl(retailer, productUrl);
        return confirmIdentity(productId, product, retailer, productUrl, request);
    }

    /*
     * Fail-closed identity gate at confirmation time: the candidate title from
     * discovery is required and run through the same Phase 2 matcher used at
     * refresh time. A valid-looking URL alone never becomes a link.
     */
    private RetailerLinkResponse confirmIdentity(Long productId, Product product,
                                                 RetailerKey retailer, String productUrl,
                                                 RetailerLinkRequest request) {
        String candidateTitle = request.productTitle() == null ? "" : request.productTitle().trim();
        if (candidateTitle.isEmpty()) {
            throw new IllegalArgumentException(
                    "productTitle is required so the candidate can be identity-validated "
                            + "against the product before linking (fail-closed).");
        }
        // Candidate proxy: price is not fetched at confirmation time; only
        // identity fields (title/SKU) participate in the matcher.
        RetailerPrice candidate = new RetailerPrice(
                retailer, null, productUrl, candidateTitle, LocalDateTime.now(), request.sku());
        ProductIdentityMatcher.Decision decision =
                identityMatcher.evaluate("", product, candidate);
        if (decision.outcome() != ProductIdentityMatcher.Outcome.MATCH) {
            String reason = decision.reason() == null || decision.reason().isBlank()
                    ? "Candidate identity could not be confirmed against the product."
                    : decision.reason();
            log.warn("[link] rejected candidate for product {}: {}", productId, reason);
            throw new IllegalArgumentException(reason);
        }
        return upsertLink(productId, product, retailer, productUrl, request.sku());
    }


    /*
     * At most one active link per (product, retailer): identical
     * re-confirmation is an idempotent no-op; a different candidate replaces
     * the previous link. Existing price rows are never touched.
     */
    private RetailerLinkResponse upsertLink(Long productId, Product product,
                                            RetailerKey retailer, String productUrl,
                                            String candidateSku) {
        String website = retailer.getDisplayName();
        RetailerLink existing = retailerLinkRepository
                .findByProductIdAndWebsite(productId, website)
                .orElse(null);

        if (existing != null && existing.getProductUrl().equals(productUrl)
                && existing.getSku() != null && existing.getSku().equals(candidateSku)) {
            log.info("[link] product {} already linked to {} ({})", productId, website, productUrl);
            return new RetailerLinkResponse(productId, website, existing.getProductUrl(),
                    existing.getSku(), "already-linked",
                    "Candidate already active for this product; nothing changed.");
        }

        String status;
        String message;
        if (existing == null) {
            retailerLinkRepository.save(new RetailerLink(product, website, productUrl, candidateSku));
            status = "linked";
            message = "Confirmed candidate is now the active " + website + " link.";
        } else {
            existing.setProductUrl(productUrl);
            existing.setSku(candidateSku);
            existing.setLinkedAt(LocalDateTime.now());
            retailerLinkRepository.save(existing);
            status = "updated";
            message = "Previous " + website + " link replaced with the confirmed candidate.";
        }
        log.info("[link] product {} {}: {} ({})", productId, status, website, productUrl);
        return new RetailerLinkResponse(productId, website, productUrl,
                candidateSku, status, message);
    }

    @Transactional(readOnly = true)
    public RetailerLinkResponse getLink(Long productId, RetailerKey retailer) {
        productService.requireProduct(productId);
        RetailerLink link = retailerLinkRepository
                .findByProductIdAndWebsite(productId, retailer.getDisplayName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active " + retailer.getDisplayName() + " link for product " + productId
                                + ". Confirm a discovered candidate first."));
        return new RetailerLinkResponse(productId, link.getWebsite(), link.getProductUrl(),
                link.getSku(), "active", "Currently active retailer link.");
    }

    private RetailerKey parseRetailer(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Retailer must not be empty.");
        }
        String value = raw.trim();
        for (RetailerKey key : RetailerKey.values()) {
            if (key.name().equalsIgnoreCase(value)
                    || key.getDisplayName().equalsIgnoreCase(value)) {
                return key;
            }
        }
        throw new IllegalArgumentException("Unknown retailer: " + value);
    }

    /*
     * Retailer-specific URL validation — never an arbitrary external URL.
     * Only retailers with an implemented validator can be linked in this
     * MVP; Vijay Sales reuses the exact same check discovery uses
     * (http/https, www.vijaysales.com, /p/{sku}/{slug} product pages only).
     */
    private void validateProductUrl(RetailerKey retailer, String productUrl) {
        if (retailer == RetailerKey.VIJAY_SALES) {
            if (!VijaySalesDiscoveryProvider.isTrackableProductUrl(productUrl)) {
                throw new IllegalArgumentException(
                        "URL is not a valid Vijay Sales product page: " + productUrl);
            }
            return;
        }
        throw new IllegalArgumentException(
                "Linking is not supported for retailer " + retailer.getDisplayName()
                        + " yet (MVP scope: Vijay Sales).");
    }
}

