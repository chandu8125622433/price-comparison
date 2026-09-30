package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.dto.PriceRefreshResponse;
import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.entity.ProductPrice;
import com.pricecompare.price_comparison.exception.ResourceNotFoundException;
import com.pricecompare.price_comparison.repository.ProductPriceRepository;
import com.pricecompare.price_comparison.repository.ProductRepository;
import com.pricecompare.price_comparison.retailer.ProductIdentityMatcher;
import com.pricecompare.price_comparison.retailer.RetailerException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.retailer.RetailerPrice;
import com.pricecompare.price_comparison.retailer.RetailerPriceProvider;
import com.pricecompare.price_comparison.retailer.RetailerRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Orchestrates a manual "refresh prices from real retailers" run for one product.
 *
 * <p>Calls every registered {@link RetailerPriceProvider} safely:
 * one provider failing (network, CAPTCHA wall, missing price element)
 * never stops the others and never deletes or overwrites existing price
 * rows with bad data. Each provider outcome is logged individually, and
 * failures are reported verbatim so real blockers stay visible.</p>
 *
 * <p>Valid {@link RetailerPrice} results are converted through the EXISTING
 * {@link ProductPriceService#addPrice} path, which upserts by
 * (productId, website) — so existing demo/sample prices and previously
 * fetched retailer prices are preserved and only refreshed, never wiped.</p>
 */
@Service
public class RetailerPriceRefreshService {

    private static final Logger log =
            LoggerFactory.getLogger(RetailerPriceRefreshService.class);

    private final ProductRepository productRepository;
    private final ProductPriceRepository productPriceRepository;
    private final RetailerRegistry retailerRegistry;
    private final ProductPriceService productPriceService;
    private final ProductIdentityMatcher identityMatcher;
    private final com.pricecompare.price_comparison.repository.RetailerLinkRepository retailerLinkRepository;
    private final ExecutorService executor;

    public RetailerPriceRefreshService(ProductRepository productRepository,
                                       ProductPriceRepository productPriceRepository,
                                       RetailerRegistry retailerRegistry,
                                       ProductPriceService productPriceService,
                                       ProductIdentityMatcher identityMatcher,
                                       com.pricecompare.price_comparison.repository.RetailerLinkRepository retailerLinkRepository) {
        this.productRepository = productRepository;
        this.productPriceRepository = productPriceRepository;
        this.retailerRegistry = retailerRegistry;
        this.productPriceService = productPriceService;
        this.identityMatcher = identityMatcher;
        this.retailerLinkRepository = retailerLinkRepository;
        this.executor = ServiceExecutors.newBoundedExecutor("price-refresh");
    }

    @PreDestroy
    public void shutdownExecutor() {
        executor.shutdown();
    }

    public PriceRefreshResponse refreshPricesForProduct(Long productId) {
        Product product = requireProduct(productId);

        List<RetailerPriceProvider> providers = retailerRegistry.allProviders();

        List<String> successful = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String, String> failureReasons = new LinkedHashMap<>();
        int pricesSaved = 0;

        // Run the independent retailer price fetches concurrently on the
        // bounded executor. URL resolution, fetch, identity validation and
        // persistence for each retailer happen on a worker thread, so a slow or
        // failing retailer never delays or aborts the others. Outcomes are
        // merged below in registry order, keeping the response lists, failure
        // reasons and saved-price count identical to the sequential run.
        List<CompletableFuture<RefreshOutcome>> futures = new ArrayList<>();
        for (RetailerPriceProvider provider : providers) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                String providerName = provider.retailer().getDisplayName();
                try {
                    List<RetailerPrice> fetched = provider.fetchPrice(
                            requireProductUrl(product, provider.retailer()));

                    if (fetched == null || fetched.isEmpty()) {
                        String reason = "Provider returned no price data.";
                        log.warn("[refresh] {} returned no data for product {}",
                                providerName, productId);
                        return RefreshOutcome.failure(reason);
                    }

                    List<String> identityReasons = new ArrayList<>();
                    int savedForProvider = persistFetchedPrices(product, fetched, identityReasons);

                    if (savedForProvider == 0) {
                        // Every fetched price was rejected by identity validation.
                        String reason = identityReasons.isEmpty()
                                ? "No retailer prices passed identity validation; skipping persistence."
                                : String.join(" | ", identityReasons);
                        log.warn("[refresh] {} saved nothing for product {}: {}",
                                providerName, productId, reason);
                        return RefreshOutcome.failure(reason);
                    }

                    log.info("[refresh] {} OK: {} price(s) saved for product {}",
                            providerName, savedForProvider, productId);
                    if (!identityReasons.isEmpty()) {
                        log.info("[refresh] {} rejected {} additional price(s) by identity for product {}: {}",
                                providerName, identityReasons.size(), productId,
                                String.join(" | ", identityReasons));
                    }
                    return RefreshOutcome.success(savedForProvider);
                } catch (Exception ex) {
                    String reason = ex.getMessage() == null
                            ? ex.getClass().getSimpleName()
                            : ex.getMessage();
                    log.warn("[refresh] {} FAILED for product {}: {}",
                            providerName, productId, reason);
                    return RefreshOutcome.failure(reason);
                }
            }, executor));
        }

        for (int i = 0; i < providers.size(); i++) {
            String providerName = providers.get(i).retailer().getDisplayName();
            RefreshOutcome outcome = futures.get(i).join();
            if (outcome.failed()) {
                failed.add(providerName);
                failureReasons.put(providerName, outcome.reason());
            } else {
                pricesSaved += outcome.saved();
                successful.add(providerName);
            }
        }

        return new PriceRefreshResponse(
                productId,
                providers.size(),
                List.copyOf(successful),
                List.copyOf(failed),
                pricesSaved,
                failureReasons
        );
    }

    private Product requireProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product not found with id: " + productId));
    }

    /**
     * Resolves the public product page URL for the given retailer. A
     * user-confirmed retailer link (Phase 4) is the active link and wins;
     * otherwise the legacy behavior applies: the price row whose website
     * matches the retailer display name is preferred, and any other
     * non-blank URL is a fallback. Providers validate the host themselves,
     * so a URL belonging to a different retailer is rejected safely instead
     * of being fetched.
     */
    private String requireProductUrl(Product product, RetailerKey retailer) {
        String linkedUrl = retailerLinkRepository
                .findByProductIdAndWebsite(product.getId(), retailer.getDisplayName())
                .map(com.pricecompare.price_comparison.entity.RetailerLink::getProductUrl)
                .filter(url -> url != null && !url.isBlank())
                .orElse(null);
        if (linkedUrl != null) {
            return linkedUrl;
        }

        List<ProductPrice> rows =
                productPriceRepository.findByProductId(product.getId());

        return rows.stream()
                .filter(row -> retailer.getDisplayName().equalsIgnoreCase(row.getWebsite()))
                .map(ProductPrice::getProductUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .or(() -> rows.stream()
                        .map(ProductPrice::getProductUrl)
                        .filter(url -> url != null && !url.isBlank())
                        .findFirst())
                .orElseThrow(() -> new RetailerException(
                        "No product URL available for retailer "
                                + retailer.getDisplayName()
                                + ". Add a price row with the public product page URL first."));
    }

    private int persistFetchedPrices(Product product,
                                     List<RetailerPrice> fetched,
                                     List<String> identityReasons) {
        int saved = 0;
        for (RetailerPrice retailerPrice : fetched) {
            // A confirmed retailer link carries a trusted SKU; when present it
            // activates the matcher's decisive SKU path against the scraped SKU.
            String expectedSku = retailerLinkRepository
                    .findByProductIdAndWebsite(product.getId(),
                            retailerPrice.retailer().getDisplayName())
                    .map(com.pricecompare.price_comparison.entity.RetailerLink::getSku)
                    .orElse("");

            ProductIdentityMatcher.Decision decision =
                    identityMatcher.evaluate(expectedSku, product, retailerPrice);

            if (decision.outcome() != ProductIdentityMatcher.Outcome.MATCH) {
                String reason = decision.reason() == null || decision.reason().isBlank()
                        ? "Retailer product identity could not be confirmed; skipping persistence."
                        : decision.reason();
                identityReasons.add(reason);
                log.warn("[refresh] Skipping persistence for product {}: {}",
                        product.getId(), reason);
                continue;
            }

            productPriceService.addPrice(
                    product.getId(),
                    retailerPrice.retailer().getDisplayName(),
                    retailerPrice.price(),
                    retailerPrice.productUrl() == null || retailerPrice.productUrl().isBlank()
                            ? productPriceRepository.findByProductId(product.getId())
                                    .stream()
                                    .findFirst()
                                    .map(p -> p.getProductUrl())
                                    .orElse("")
                            : retailerPrice.productUrl(),
                    retailerPrice.sku()
            );
            saved++;
        }
        return saved;
    }

    /**
     * Per-provider refresh outcome carried back from the executor threads.
     * A failed provider carries its verbatim reason; successful ones carry the
     * number of prices persisted (only real, identity-validated prices).
     */
    private record RefreshOutcome(boolean failed, String reason, int saved) {
        static RefreshOutcome success(int saved) {
            return new RefreshOutcome(false, "", saved);
        }

        static RefreshOutcome failure(String reason) {
            return new RefreshOutcome(true, reason, 0);
        }
    }
}