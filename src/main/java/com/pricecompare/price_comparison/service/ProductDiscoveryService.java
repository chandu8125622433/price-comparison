package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.dto.DiscoveredCandidate;
import com.pricecompare.price_comparison.dto.ProductDiscoveryResponse;
import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.retailer.discovery.DiscoveredProduct;
import com.pricecompare.price_comparison.retailer.discovery.ProductDiscoveryProvider;
import com.pricecompare.price_comparison.retailer.discovery.ProductDiscoveryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Phase 3 MVP product-discovery coordinator.
 *
 * <p>Builds a plain search query from the product's own fields, asks every
 * registered {@link ProductDiscoveryProvider} for candidates, de-duplicates
 * them across providers, caps the total, and returns them directly —
 * nothing is persisted by discovery.</p>
 *
 * <p>The flow stays: Discovery → Candidate → Identity validation →
 * Price provider → persisted {@code ProductPrice}. A provider failing
 * (network, blocked endpoint, unparsable response) never crashes the
 * request; its failure is reported with a reason instead, and the other
 * providers' results are still returned.</p>
 */
@Service
public class ProductDiscoveryService {

    private static final Logger log =
            LoggerFactory.getLogger(ProductDiscoveryService.class);

    private final ProductService productService;
    private final ProductDiscoveryRegistry discoveryRegistry;
    private final int maxTotalCandidates;
    private final ExecutorService executor;

    public ProductDiscoveryService(ProductService productService,
                                   ProductDiscoveryRegistry discoveryRegistry,
                                   @Value("${app.discovery.max-candidates:10}") int maxTotalCandidates) {
        this.productService = productService;
        this.discoveryRegistry = discoveryRegistry;
        this.maxTotalCandidates = Math.max(1, maxTotalCandidates);
        this.executor = ServiceExecutors.newBoundedExecutor("discovery");
    }

    @PreDestroy
    public void shutdownExecutor() {
        executor.shutdown();
    }

    public ProductDiscoveryResponse discoverForProduct(Long productId) {
        Product product = productService.requireProduct(productId);
        String query = buildSearchQuery(product);

        List<ProductDiscoveryProvider> providers = discoveryRegistry.allProviders();
        List<String> failed = new ArrayList<>();
        Map<String, String> failureReasons = new LinkedHashMap<>();
        List<DiscoveredProduct> candidates = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();

        if (query.isEmpty()) {
            log.warn("[discovery] product {} has no searchable identity; skipping providers",
                    productId);
        } else {
            // Run the independent retailer discovery providers concurrently on
            // the bounded executor (blocking HTTP calls never pile up on the
            // servlet thread; it only blocks during the final aggregation
            // below). Every provider is fully consumed, then the outcomes are
            // merged in registry order so ordering, first-wins URL
            // de-duplication and the configured candidate cap are unchanged.
            List<CompletableFuture<DiscoveryOutcome>> futures = new ArrayList<>();
            for (ProductDiscoveryProvider provider : providers) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return DiscoveryOutcome.success(provider.discover(query));
                    } catch (Exception ex) {
                        return DiscoveryOutcome.failure(failureReason(ex));
                    }
                }, executor));
            }
            for (int i = 0; i < providers.size(); i++) {
                ProductDiscoveryProvider provider = providers.get(i);
                String retailerName = provider.retailer().getDisplayName();
                DiscoveryOutcome outcome = futures.get(i).join();
                if (outcome.failed()) {
                    failed.add(retailerName);
                    failureReasons.put(retailerName, outcome.reason());
                    log.warn("[discovery] {} failed for product {}: {}",
                            retailerName, productId, outcome.reason());
                    continue;
                }
                int added = 0;
                for (DiscoveredProduct candidate : outcome.products()) {
                    if (candidates.size() >= maxTotalCandidates) {
                        break;
                    }
                    if (seenUrls.add(DiscoveredProduct.dedupeKey(candidate.productUrl()))) {
                        candidates.add(candidate);
                        added++;
                    }
                }
                log.info("[discovery] {}: {} candidate(s) for product {}",
                        retailerName, added, productId);
                if (candidates.size() >= maxTotalCandidates) {
                    break;
                }
            }
        }

        return new ProductDiscoveryResponse(
                productId,
                product.getName(),
                query,
                query.isEmpty() ? 0 : providers.size(),
                List.copyOf(failed),
                failureReasons,
                candidates.stream().map(DiscoveredCandidate::from).toList());
    }

    /**
     * Query-based discovery used by the cache-first search: runs the same
     * registered providers against a free-text query (no persisted product
     * required) and returns de-duplicated, capped candidates. One provider
     * failing (credentials, blocked endpoint, unparsable response) is logged
     * and skipped — it never prevents another retailer from contributing.
     */
    public List<DiscoveredProduct> discoverCandidates(String query) {
        List<DiscoveredProduct> candidates = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<ProductDiscoveryProvider> providers = discoveryRegistry.allProviders();

        // Same concurrency pattern as discoverForProduct: every provider runs
        // on the bounded executor and is fully consumed; outcomes are merged
        // in registry order below, preserving ordering and de-duplication.
        List<CompletableFuture<DiscoveryOutcome>> futures = new ArrayList<>();
        for (ProductDiscoveryProvider provider : providers) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return DiscoveryOutcome.success(provider.discover(query));
                } catch (Exception ex) {
                    log.warn("[discovery] {} failed for query '{}': {}",
                            provider.retailer().getDisplayName(), query, failureReason(ex));
                    return DiscoveryOutcome.failure(failureReason(ex));
                }
            }, executor));
        }
        for (int i = 0; i < providers.size(); i++) {
            ProductDiscoveryProvider provider = providers.get(i);
            DiscoveryOutcome outcome = futures.get(i).join();
            if (outcome.failed()) {
                continue;
            }
            int added = 0;
            for (DiscoveredProduct candidate : outcome.products()) {
                if (candidates.size() >= maxTotalCandidates) {
                    break;
                }
                if (seenUrls.add(DiscoveredProduct.dedupeKey(candidate.productUrl()))) {
                    candidates.add(candidate);
                    added++;
                }
            }
            log.info("[discovery] {}: {} candidate(s) for query '{}'",
                    provider.retailer().getDisplayName(), added, query);
            if (candidates.size() >= maxTotalCandidates) {
                break;
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * Simple deterministic search query from the product's own fields
     * (name first, brand+category fallback). No NLP by design.
     */
    private String buildSearchQuery(Product product) {
        String name = product.getName() == null ? "" : product.getName().trim();
        if (!name.isEmpty()) {
            return name;
        }
        String brand = product.getBrand() == null ? "" : product.getBrand().trim();
        String category = product.getCategory() == null ? "" : product.getCategory().trim();
        return (brand + " " + category).replaceAll("\\s+", " ").trim();
    }

    private static String failureReason(Throwable ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? "discovery failed"
                : ex.getMessage();
    }

    /**
     * Per-provider result carried back from the executor threads. {@code failed}
     * providers never interrupt the others; their reason is surfaced verbatim.
     */
    private record DiscoveryOutcome(boolean failed, String reason, List<DiscoveredProduct> products) {
        static DiscoveryOutcome success(List<DiscoveredProduct> products) {
            return new DiscoveryOutcome(false, "", products);
        }

        static DiscoveryOutcome failure(String reason) {
            return new DiscoveryOutcome(true, reason, List.of());
        }
    }
}
