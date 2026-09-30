package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.dto.MultiRetailerSearchResponse;
import com.pricecompare.price_comparison.dto.RetailerOffer;
import com.pricecompare.price_comparison.dto.RetailerOfferSummary;
import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.entity.ProductPrice;
import com.pricecompare.price_comparison.entity.RetailerLink;
import com.pricecompare.price_comparison.repository.ProductPriceRepository;
import com.pricecompare.price_comparison.repository.RetailerLinkRepository;
import com.pricecompare.price_comparison.retailer.ProductIdentityMatcher;
import com.pricecompare.price_comparison.retailer.RetailerConfigurationException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.retailer.RetailerPrice;
import com.pricecompare.price_comparison.retailer.discovery.DiscoveredProduct;
import com.pricecompare.price_comparison.retailer.discovery.ProductDiscoveryProvider;
import com.pricecompare.price_comparison.retailer.discovery.ProductDiscoveryRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Phase 7 multi-retailer search coordinator: runs every registered discovery
 * provider for one product, identity-matches each candidate, and layers the
 * persisted VERIFIED price on top. Read-only: never persists prices.
 *
 * <p>Only the blocking discovery (network) phase runs concurrently per
 * retailer on a bounded executor. Confirmed-title resolution, identity
 * matching, verified-price layering and summary assembly stay strictly
 * sequential in the fixed {@link #REQUIRED_RETAILERS} order, preserving the
 * Vijay-Sales-first confirmed-title dependency exactly as the sequential
 * run produced it.</p>
 */
@Service
public class MultiRetailerSearchService {

    private static final Logger log = LoggerFactory.getLogger(MultiRetailerSearchService.class);

    private final ProductService productService;
    private final ProductDiscoveryRegistry discoveryRegistry;
    private final RetailerLinkRepository retailerLinkRepository;
    private final ProductPriceRepository productPriceRepository;
    private final ProductIdentityMatcher identityMatcher;
    private final ExecutorService executor;

    public MultiRetailerSearchService(ProductService productService,
                                      ProductDiscoveryRegistry discoveryRegistry,
                                      RetailerLinkRepository retailerLinkRepository,
                                      ProductPriceRepository productPriceRepository,
                                      ProductIdentityMatcher identityMatcher) {
        this.productService = productService;
        this.discoveryRegistry = discoveryRegistry;
        this.retailerLinkRepository = retailerLinkRepository;
        this.productPriceRepository = productPriceRepository;
        this.identityMatcher = identityMatcher;
        this.executor = ServiceExecutors.newBoundedExecutor("multi-retailer-search");
    }

    @PreDestroy
    public void shutdownExecutor() {
        executor.shutdown();
    }

    /**
     * The five retailers the user-facing comparison must ALWAYS represent, in
     * display order. A required retailer without a registered provider still
     * appears with an explicit placeholder status (see {@link #placeholderSummary}).
     */
    private static final List<RetailerKey> REQUIRED_RETAILERS = List.of(
            RetailerKey.VIJAY_SALES,
            RetailerKey.RELIANCE_DIGITAL,
            RetailerKey.FLIPKART,
            RetailerKey.AMAZON,
            RetailerKey.CROMA);

    public MultiRetailerSearchResponse searchForProduct(Long productId) {
        Product product = productService.requireProduct(productId);
        String query = product.getName() == null ? "" : product.getName();

        // Same single DB read at the same point as the sequential run: the
        // confirmed-link existence is resolved BEFORE any retailer work starts.
        boolean hasAnyConfirmedLink =
                !retailerLinkRepository.findByProductId(productId).isEmpty();

        // Phase 1 — discovery only, concurrently: every REGISTERED required
        // retailer's discover() call (blocking network) runs on the bounded
        // executor, so no retailer waits for another. Retailers without a
        // provider are pure placeholders and never occupy a worker. The
        // user-confirmed product identity (the confirmed candidate's title,
        // which carries the exact model code such as AS-Q20ENZE) is
        // deliberately NOT resolved here.
        Map<RetailerKey, DiscoveryResult> discoveries = discoverConcurrently(product);

        // Phase 2 — the unchanged sequential pipeline, in REQUIRED_RETAILERS
        // order. Vijay Sales is evaluated first, so the title of its
        // SKU-matched confirmed candidate is known before the other retailers
        // are identity-matched; if Vijay Sales has no confirmed link, the next
        // linked retailer resolves the title for the retailers after it —
        // first-wins, exactly as before.
        String[] confirmedTitle = {""};
        List<RetailerOfferSummary> summaries = new ArrayList<>();
        for (RetailerKey key : REQUIRED_RETAILERS) {
            DiscoveryResult discovery = discoveries.get(key);
            summaries.add(discovery == null
                    ? placeholderSummary(key)
                    : searchOne(productId, product, discovery, confirmedTitle, hasAnyConfirmedLink));
        }
        return new MultiRetailerSearchResponse(
                productId, product.getName(), query, summaries, lowestVerified(summaries));
    }

    /**
     * Explicit representation for a required retailer that has no registered
     * discovery provider. The retailer must never silently disappear from the
     * comparison and must never be shown with a fabricated price.
     */
    private RetailerOfferSummary placeholderSummary(RetailerKey key) {
        return switch (key) {
            case CROMA -> new RetailerOfferSummary(key.getDisplayName(), "CURRENTLY_UNAVAILABLE",
                    "Croma is currently unavailable: no permitted automated access path. "
                            + "No price is shown.",
                    List.of());
            case AMAZON -> new RetailerOfferSummary(key.getDisplayName(), "OFFICIAL_ACCESS_REQUIRED",
                    "Official Amazon India API access is not configured. No price is shown "
                            + "until authorized access is available.",
                    List.of());
            default -> new RetailerOfferSummary(key.getDisplayName(), "OFFICIAL_ACCESS_REQUIRED",
                    "Official API access is not configured for this retailer. No price is shown.",
                    List.of());
        };
    }

    /**
     * Phase 1 — discovery only, concurrently. Every REGISTERED required
     * retailer's blocking {@code discover()} call runs on the bounded
     * executor; outcomes are joined in {@link #REQUIRED_RETAILERS} order.
     * Retailers without a provider never execute (pure placeholders).
     */
    private Map<RetailerKey, DiscoveryResult> discoverConcurrently(Product product) {
        Map<RetailerKey, CompletableFuture<DiscoveryResult>> futures = new LinkedHashMap<>();
        for (RetailerKey key : REQUIRED_RETAILERS) {
            ProductDiscoveryProvider provider = discoveryRegistry.findByKey(key).orElse(null);
            if (provider == null) {
                continue;
            }
            futures.put(key, CompletableFuture.supplyAsync(
                    () -> discoverOne(product, provider), executor));
        }
        Map<RetailerKey, DiscoveryResult> results = new LinkedHashMap<>();
        for (Map.Entry<RetailerKey, CompletableFuture<DiscoveryResult>> entry : futures.entrySet()) {
            results.put(entry.getKey(), entry.getValue().join());
        }
        return results;
    }

    /**
     * One retailer's discovery on a worker thread. A blank product name never
     * reaches the provider (identical to the sequential run); any other
     * failure is captured per retailer and re-dispatched through the
     * unchanged catch chain in {@link #searchOne}, preserving the exact
     * status mapping and the other retailers' isolation.
     */
    private DiscoveryResult discoverOne(Product product, ProductDiscoveryProvider provider) {
        try {
            if (product.getName() == null) {
                return DiscoveryResult.success(provider, List.of());
            }
            return DiscoveryResult.success(provider, provider.discover(product.getName()));
        } catch (Exception ex) {
            return DiscoveryResult.failure(provider, ex);
        }
    }

    /**
     * Per-retailer discovery outcome carried back from the executor threads:
     * either the discovered candidate list, or the failure to re-dispatch
     * through the unchanged per-retailer catch chain.
     */
    private record DiscoveryResult(ProductDiscoveryProvider provider,
                                   List<DiscoveredProduct> candidates,
                                   Exception failure) {

        static DiscoveryResult success(ProductDiscoveryProvider provider,
                                       List<DiscoveredProduct> candidates) {
            return new DiscoveryResult(provider, candidates, null);
        }

        static DiscoveryResult failure(ProductDiscoveryProvider provider, Exception failure) {
            return new DiscoveryResult(provider, List.of(), failure);
        }
    }

    private RetailerOfferSummary searchOne(Long productId, Product product, DiscoveryResult discovery,
                                           String[] confirmedTitle, boolean hasAnyConfirmedLink) {
        ProductDiscoveryProvider provider = discovery.provider();
        String retailer = provider.retailer().getDisplayName();
        try {
            // A discovery that failed on the worker thread fails THIS retailer
            // here through the exact same catch chain as the sequential run;
            // the other retailers are never affected.
            if (discovery.failure() != null) {
                throw discovery.failure();
            }
            List<DiscoveredProduct> candidates = discovery.candidates();
            String linkedSku = linkedSku(productId, retailer);

            // Resolve the confirmed product title from THIS retailer's own
            // SKU-matched candidate: the link SKU identifies the exact product.
            if (!linkedSku.isBlank() && confirmedTitle[0].isBlank()) {
                candidates.stream()
                        .filter(candidate -> linkedSku.equalsIgnoreCase(candidate.sku())
                                && !candidate.productTitle().isBlank())
                        .findFirst()
                        .ifPresent(candidate -> confirmedTitle[0] = candidate.productTitle());
            }

            List<RetailerOffer> offers = new ArrayList<>();
            for (DiscoveredProduct candidate : candidates) {
                String expectedSku = linkedSku;
                String expectedTitle = expectedSku.isBlank() ? confirmedTitle[0] : "";
                if (expectedSku.isBlank() && expectedTitle.isBlank() && hasAnyConfirmedLink) {
                    // A confirmed identity exists for this product but could not be
                    // resolved here: fail closed rather than risk accepting a
                    // different model of the same brand.
                    continue;
                }
                                 ProductIdentityMatcher.Decision decision = identityMatcher.evaluateConfirmed(
                         expectedSku,
                         expectedTitle,
                         product,
                         new RetailerPrice(provider.retailer(), null, candidate.productUrl(),
                                 candidate.productTitle(), null, candidate.sku(),
                                 candidate.model()));
                if (decision.outcome() == ProductIdentityMatcher.Outcome.MATCH) {
                    offers.add(RetailerOffer.fromCandidate(candidate));
                }
            }
            if (offers.isEmpty()) {
                return new RetailerOfferSummary(retailer, "NO_MATCHING_PRODUCT",
                        "Discovered candidates did not match this product's identity.", List.of());
            }

            Optional<ProductPrice> verified = verifiedPrice(productId, retailer, linkedSku);
            if (verified.isPresent()) {
                offers.add(0, RetailerOffer.fromVerified(verified.get(), retailer));
                return new RetailerOfferSummary(retailer, "VERIFIED_PRICE",
                        "Verified current price from the confirmed retailer link.", offers);
            }
            return new RetailerOfferSummary(retailer, "MATCHED_CANDIDATES",
                    "Identity-matched candidates found; confirm a link and refresh for a live price.",
                    offers);
        } catch (RetailerConfigurationException ex) {
            log.info("[multi-retailer] {} configuration required for product {}: {}",
                    retailer, product.getId(), ex.getMessage());
            return new RetailerOfferSummary(retailer, "CONFIGURATION_REQUIRED",
                    ex.getMessage(), List.of());
        } catch (Exception ex) {
            String reason = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? "retailer discovery failed"
                    : ex.getMessage();
            log.warn("[multi-retailer] {} failed for product {}: {}", retailer, product.getId(), reason);
            return new RetailerOfferSummary(retailer, "PROVIDER_ERROR", reason, List.of());
        }
    }

    private String linkedSku(Long productId, String website) {
        return retailerLinkRepository.findByProductIdAndWebsite(productId, website)
                .map(RetailerLink::getSku)
                .orElse("");
    }

    /**
     * A persisted price counts as VERIFIED only when a confirmed retailer
     * link exists AND the stored row carries that link's SKU — i.e. it was
     * produced by the identity-validated refresh pipeline.
     */
    private Optional<ProductPrice> verifiedPrice(Long productId, String website, String linkedSku) {
        if (linkedSku == null || linkedSku.isBlank()) {
            return Optional.empty();
        }
        return productPriceRepository.findByProductIdAndWebsite(productId, website)
                .filter(row -> row.getSku() != null
                        && linkedSku.equalsIgnoreCase(row.getSku().trim())
                        && RetailerKey.hasOfficialProductUrl(row.getWebsite(), row.getProductUrl()));
    }

    private MultiRetailerSearchResponse.LowestVerifiedPrice lowestVerified(
            List<RetailerOfferSummary> summaries) {
        BigDecimal lowest = null;
        BigDecimal highest = null;
        RetailerOffer best = null;
        for (RetailerOfferSummary summary : summaries) {
            for (RetailerOffer offer : summary.offers()) {
                if (!offer.verified() || offer.price() == null || offer.price().isBlank()) {
                    continue;
                }
                BigDecimal price = new BigDecimal(offer.price());
                if (lowest == null || price.compareTo(lowest) < 0) {
                    lowest = price;
                    best = offer;
                }
                if (highest == null || price.compareTo(highest) > 0) {
                    highest = price;
                }
            }
        }
        if (best == null) {
            return null;
        }
        BigDecimal difference = highest == null || highest.compareTo(lowest) == 0
                ? null
                : highest.subtract(lowest);
        return new MultiRetailerSearchResponse.LowestVerifiedPrice(
                best.retailer(), lowest, best.productUrl(), best.lastUpdated(), difference);
    }
}
