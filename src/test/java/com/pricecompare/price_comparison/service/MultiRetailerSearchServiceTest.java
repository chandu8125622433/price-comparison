package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.dto.MultiRetailerSearchResponse;
import com.pricecompare.price_comparison.dto.RetailerOfferSummary;
import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.entity.ProductPrice;
import com.pricecompare.price_comparison.entity.RetailerLink;
import com.pricecompare.price_comparison.repository.ProductPriceRepository;
import com.pricecompare.price_comparison.repository.RetailerLinkRepository;
import com.pricecompare.price_comparison.retailer.ProductIdentityMatcher;
import com.pricecompare.price_comparison.retailer.RetailerConfigurationException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.retailer.discovery.DiscoveredProduct;
import com.pricecompare.price_comparison.retailer.discovery.ProductDiscoveryProvider;
import com.pricecompare.price_comparison.retailer.discovery.ProductDiscoveryRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 7: normalized multi-retailer search — identity-matched candidates
 * reported, verified-priced offers marked verified with low/high computed,
 * candidate URLs validated, and a retailer failing never affects others.
 * No network.
 */
class MultiRetailerSearchServiceTest {

    private static final String SAMSUNG_URL =
            "https://www.vijaysales.com/p/258479/samsung-139-cm-55-inch-4k-ultra-hd-smart-tv";
    private static final String SAMSUNG_TITLE =
            "Samsung 139 cm (55 inch) 4K Ultra HD Smart LED TV UA55";
    private static final String SAMSUNG_SKU = "258479";

    private final Product product =
            new Product("Samsung 55 inch 4K Smart TV", "Samsung", "UA55", "Television");

    private static final DiscoveredProduct vijayCandidate =
            new DiscoveredProduct(
                    RetailerKey.VIJAY_SALES, SAMSUNG_URL, SAMSUNG_TITLE, SAMSUNG_SKU,
                    "", "89990", "In stock", "78990", "INR");

    private final ProductService productService = mock(ProductService.class);
    private final RetailerLinkRepository linkRepository = mock(RetailerLinkRepository.class);
    private final ProductPriceRepository priceRepository = mock(ProductPriceRepository.class);
    private final ProductDiscoveryRegistry registry = mock(ProductDiscoveryRegistry.class);

    private MultiRetailerSearchService service(ProductDiscoveryProvider... providers) {
        when(registry.findByKey(any())).thenReturn(Optional.empty());
        for (ProductDiscoveryProvider provider : providers) {
            when(registry.findByKey(provider.retailer())).thenReturn(Optional.of(provider));
        }
        when(productService.requireProduct(1L)).thenReturn(product);
        when(linkRepository.findByProductIdAndWebsite(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(linkRepository.findByProductId(anyLong())).thenReturn(List.of());
        when(priceRepository.findByProductIdAndWebsite(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        return new MultiRetailerSearchService(
                productService, registry, linkRepository, priceRepository,
                new ProductIdentityMatcher());
    }

    /* 1. Identity-matched candidate is reported (no verified price yet). */
    @Test
    void matchedCandidateIsReported() {
        MultiRetailerSearchService svc = service(vsProvider(true));
        MultiRetailerSearchResponse r = svc.searchForProduct(1L);

        // All five required retailers are ALWAYS represented — a retailer
        // without a provider still appears with an explicit status.
        assertEquals(5, r.retailers().size());
        RetailerOfferSummary vs = r.retailers().get(0);
        assertEquals("MATCHED_CANDIDATES", vs.status());
        assertEquals("OFFICIAL_ACCESS_REQUIRED", r.retailers().get(2).status());
        assertEquals("OFFICIAL_ACCESS_REQUIRED", r.retailers().get(3).status());
        assertEquals("CURRENTLY_UNAVAILABLE", r.retailers().get(4).status());
        assertEquals("Croma", r.retailers().get(4).retailer());
        assertEquals(1, vs.offers().size());
        assertEquals(false, vs.offers().get(0).verified());
        assertEquals(SAMSUNG_SKU, vs.offers().get(0).sku());
        assertNull(r.lowestVerifiedPrice());
    }

    /* 2. A wrong-family candidate (iPad Air M3) is rejected -> NO_MATCH. */
    @Test
    void wrongFamilyCandidateIsRejected() {
        ProductDiscoveryProvider p = mock(ProductDiscoveryProvider.class);
        when(p.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        try {
            when(p.discover(anyString())).thenReturn(List.of(new DiscoveredProduct(
                    RetailerKey.VIJAY_SALES,
                    "https://www.vijaysales.com/p/P238533/apple-ipad-air-m3-13-2025",
                    "APPLE IPAD AIR M3 2025 13 WIFI 256GB STARLIGHT",
                    "238533", "", "", "In stock", "", "INR")));
        } catch (Exception ignored) {
        }

        MultiRetailerSearchService svc = service(p);
        MultiRetailerSearchResponse r = svc.searchForProduct(1L);

        assertEquals("NO_MATCHING_PRODUCT", r.retailers().get(0).status());
        assertTrue(r.retailers().get(0).offers().isEmpty());
    }

    /* 3. Config-required retailer continues while others still report. */
    @Test
    void configurationFailureIsIsolated() {
        ProductDiscoveryProvider flipkart = mock(ProductDiscoveryProvider.class);
        when(flipkart.retailer()).thenReturn(RetailerKey.FLIPKART);
        try {
            when(flipkart.discover(anyString())).thenThrow(
                    new RetailerConfigurationException(
                            "Flipkart Affiliate API credentials are not configured."));
        } catch (Exception ignored) {
        }

        MultiRetailerSearchService svc = service(flipkart, vsProvider(true));
        MultiRetailerSearchResponse r = svc.searchForProduct(1L);

        assertEquals(5, r.retailers().size());
        // Flipkart failed on its own configuration only...
        assertEquals("CONFIGURATION_REQUIRED", summaryFor(r, "Flipkart").status());
        // ...while Vijay Sales still reported its identity-matched candidate.
        assertEquals("MATCHED_CANDIDATES", summaryFor(r, "Vijay Sales").status());
    }

    private static RetailerOfferSummary summaryFor(MultiRetailerSearchResponse response,
                                                   String retailer) {
        return response.retailers().stream()
                .filter(summary -> summary.retailer().equals(retailer))
                .findFirst()
                .orElseThrow();
    }

    /* 4. Wrong LG model is rejected; the exact confirmed model is accepted. */
    @Test
    void wrongLgModelIsRejectedAndExactConfirmedModelIsAccepted() {
        Product lgAc = new Product("LG 1.5 Ton 5 Star Split AC", "LG", "LS-Q18YNZA", "AC");
        RetailerLink link = new RetailerLink(lgAc, "Vijay Sales",
                "https://www.vijaysales.com/p/256812/lg-1-5-ton-5-star-inverter-split-ac-as-q20enze",
                "256812");

        ProductDiscoveryProvider vs = mock(ProductDiscoveryProvider.class);
        when(vs.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        try {
            when(vs.discover(anyString())).thenReturn(List.of(new DiscoveredProduct(
                    RetailerKey.VIJAY_SALES,
                    "https://www.vijaysales.com/p/256812/lg-1-5-ton-5-star-inverter-split-ac-as-q20enze",
                    "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)",
                    "256812", "", "56000", "In stock", "48000", "INR")));
        } catch (Exception ignored) {
        }

        ProductDiscoveryProvider rd = mock(ProductDiscoveryProvider.class);
        when(rd.retailer()).thenReturn(RetailerKey.RELIANCE_DIGITAL);
        try {
            when(rd.discover(anyString())).thenReturn(List.of(
                    new DiscoveredProduct(RetailerKey.RELIANCE_DIGITAL,
                            "https://www.reliancedigital.in/product/lg-1-5-ton-5-star-inverter-split-ac-as-q19cnze1-9000001",
                            "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q19CNZE1)",
                            "9000001", "", "", "In stock", "42990", "INR"),
                    new DiscoveredProduct(RetailerKey.RELIANCE_DIGITAL,
                            "https://www.reliancedigital.in/product/lg-1-5-ton-5-star-inverter-split-ac-as-q20enze-9000002",
                            "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)",
                            "9000002", "", "", "In stock", "45490", "INR")));
        } catch (Exception ignored) {
        }

        MultiRetailerSearchService svc = service(vs, rd);

        // Register the confirmed-link stubs AFTER the helper's generic stubs —
        // with Mockito the last matching stub wins.
        when(productService.requireProduct(6L)).thenReturn(lgAc);
        when(linkRepository.findByProductId(6L)).thenReturn(List.of(link));
        when(linkRepository.findByProductIdAndWebsite(6L, "Vijay Sales"))
                .thenReturn(Optional.of(link));

        MultiRetailerSearchResponse r = svc.searchForProduct(6L);

        // The confirmed Vijay Sales candidate (link SKU 256812) is identity-matched.
        RetailerOfferSummary vsSummary = summaryFor(r, "Vijay Sales");
        assertEquals("MATCHED_CANDIDATES", vsSummary.status());
        assertEquals(1, vsSummary.offers().size());
        assertEquals("256812", vsSummary.offers().get(0).sku());

        // Reliance Digital: the different LG model (AS-Q19CNZE1) is rejected and
        // only the exact confirmed model (AS-Q20ENZE) is reported.
        RetailerOfferSummary rdSummary = summaryFor(r, "Reliance Digital");
        assertEquals("MATCHED_CANDIDATES", rdSummary.status());
        assertEquals(1, rdSummary.offers().size());
        assertEquals("9000002", rdSummary.offers().get(0).sku());
        assertTrue(rdSummary.offers().get(0).title().contains("AS-Q20ENZE"));
    }

    /* 5. Retailer ordering stays REQUIRED_RETAILERS even when providers finish
     * in reverse order (Vijay Sales slowest): discovery runs concurrently, the
     * response is still assembled in the fixed order. */
    @Test
    void retailerOrderIsPreservedWhenProvidersFinishInReverse() {
        MultiRetailerSearchService svc = service(
                delayedProvider(RetailerKey.VIJAY_SALES, 120),
                delayedProvider(RetailerKey.RELIANCE_DIGITAL, 0),
                delayedProvider(RetailerKey.FLIPKART, 60));
        MultiRetailerSearchResponse r = svc.searchForProduct(1L);

        assertEquals(5, r.retailers().size());
        assertEquals("Vijay Sales", r.retailers().get(0).retailer());
        assertEquals("Reliance Digital", r.retailers().get(1).retailer());
        assertEquals("Flipkart", r.retailers().get(2).retailer());
        assertEquals("Amazon", r.retailers().get(3).retailer());
        assertEquals("Croma", r.retailers().get(4).retailer());
        // Every retailer reported from its OWN discovery result.
        assertEquals("MATCHED_CANDIDATES", r.retailers().get(0).status());
        assertEquals("MATCHED_CANDIDATES", r.retailers().get(1).status());
        assertEquals("MATCHED_CANDIDATES", r.retailers().get(2).status());
    }

    /* 6. The cross-retailer confirmed-title dependency: Vijay Sales has NO
     * confirmed link (fails closed), Reliance Digital resolves the confirmed
     * title from its own link, and Flipkart — which has no link of its own —
     * matches through that RD-resolved title. */
    @Test
    void relianceDigitalResolvesTitleWhenVijaySalesHasNoLink() {
        Product lgAc = new Product("LG 1.5 Ton 5 Star Split AC", "LG", "LS-Q18YNZA", "AC");
        RetailerLink rdLink = new RetailerLink(lgAc, "Reliance Digital",
                "https://www.reliancedigital.in/product/lg-1-5-ton-5-star-inverter-split-ac-as-q20enze-9000002",
                "9000002");

        ProductDiscoveryProvider vs = providerReturning(RetailerKey.VIJAY_SALES, List.of(
                new DiscoveredProduct(RetailerKey.VIJAY_SALES,
                        "https://www.vijaysales.com/p/111/vg-1-5-ton-5-star-inverter-split-ac",
                        "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q19CNZE1)",
                        "111", "", "", "In stock", "", "INR")));
        ProductDiscoveryProvider rd = providerReturning(RetailerKey.RELIANCE_DIGITAL, List.of(
                new DiscoveredProduct(RetailerKey.RELIANCE_DIGITAL,
                        "https://www.reliancedigital.in/product/lg-as-q19cnze1-9000001",
                        "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q19CNZE1)",
                        "9000001", "", "", "In stock", "", "INR"),
                new DiscoveredProduct(RetailerKey.RELIANCE_DIGITAL,
                        "https://www.reliancedigital.in/product/lg-as-q20enze-9000002",
                        "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)",
                        "9000002", "", "", "In stock", "", "INR")));
        ProductDiscoveryProvider fk = providerReturning(RetailerKey.FLIPKART, List.of(
                new DiscoveredProduct(RetailerKey.FLIPKART,
                        "https://www.flipkart.com/lg-1-5-ton-5-star-inverter-split-ac-as-q20enze/p/itmfk1",
                        "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)",
                        "FKSKU1", "", "", "In stock", "", "INR")));

        MultiRetailerSearchService svc = service(vs, rd, fk);
        when(productService.requireProduct(7L)).thenReturn(lgAc);
        when(linkRepository.findByProductId(7L)).thenReturn(List.of(rdLink));
        when(linkRepository.findByProductIdAndWebsite(7L, "Reliance Digital"))
                .thenReturn(Optional.of(rdLink));

        MultiRetailerSearchResponse r = svc.searchForProduct(7L);

        // Vijay Sales has no link and the confirmed title is not yet resolved
        // at its turn: fails closed, exactly like the sequential run.
        assertEquals("NO_MATCHING_PRODUCT", summaryFor(r, "Vijay Sales").status());

        // Reliance Digital's own link SKU resolves the confirmed title...
        RetailerOfferSummary rdSummary = summaryFor(r, "Reliance Digital");
        assertEquals("MATCHED_CANDIDATES", rdSummary.status());
        assertEquals(1, rdSummary.offers().size());
        assertEquals("9000002", rdSummary.offers().get(0).sku());

        // ...and Flipkart matches through the RD-resolved confirmed title.
        RetailerOfferSummary fkSummary = summaryFor(r, "Flipkart");
        assertEquals("MATCHED_CANDIDATES", fkSummary.status());
        assertEquals(1, fkSummary.offers().size());
        assertTrue(fkSummary.offers().get(0).title().contains("AS-Q20ENZE"));
    }

    /* 7. Discovery truly runs concurrently: both providers must be inside
     * discover() at the same time before either is allowed to finish. */
    @Test
    void discoveryRunsConcurrently() {
        CountDownLatch bothInside = new CountDownLatch(2);
        MultiRetailerSearchService svc = service(
                concurrentProvider(RetailerKey.VIJAY_SALES, bothInside),
                concurrentProvider(RetailerKey.RELIANCE_DIGITAL, bothInside));

        MultiRetailerSearchResponse r = svc.searchForProduct(1L);

        assertEquals("MATCHED_CANDIDATES", summaryFor(r, "Vijay Sales").status());
        assertEquals("MATCHED_CANDIDATES", summaryFor(r, "Reliance Digital").status());
    }

    /* 8. A generic provider failure during concurrent discovery is isolated:
     * the failing retailer reports PROVIDER_ERROR with its verbatim reason
     * while the other retailers still report normally. */
    @Test
    void providerFailureDuringParallelDiscoveryIsIsolated() {
        ProductDiscoveryProvider vs = mock(ProductDiscoveryProvider.class);
        when(vs.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        try {
            when(vs.discover(anyString())).thenThrow(new RuntimeException("vs network down"));
        } catch (Exception ignored) {
        }

        MultiRetailerSearchService svc = service(vs,
                delayedProvider(RetailerKey.RELIANCE_DIGITAL, 0));
        MultiRetailerSearchResponse r = svc.searchForProduct(1L);

        RetailerOfferSummary vsSummary = summaryFor(r, "Vijay Sales");
        assertEquals("PROVIDER_ERROR", vsSummary.status());
        assertEquals("vs network down", vsSummary.message());
        assertTrue(vsSummary.offers().isEmpty());

        assertEquals("MATCHED_CANDIDATES", summaryFor(r, "Reliance Digital").status());
        // Placeholders untouched.
        assertEquals("OFFICIAL_ACCESS_REQUIRED", r.retailers().get(2).status());
        assertEquals("CURRENTLY_UNAVAILABLE", r.retailers().get(4).status());
    }

    /* 9. First-wins confirmed-title resolution: the FIRST SKU-matched
     * candidate of the linked retailer provides the confirmed title that a
     * later linkless retailer matches against. */
    @Test
    void confirmedTitleIsResolvedFromFirstSkuMatchedCandidate() {
        Product tv = new Product("Samsung 55 inch 4K Smart TV", "Samsung", "UA55", "Television");
        RetailerLink vsLink = new RetailerLink(tv, "Vijay Sales", SAMSUNG_URL, SAMSUNG_SKU);

        // Two SKU-matched candidates: the first one's title must win.
        ProductDiscoveryProvider vs = providerReturning(RetailerKey.VIJAY_SALES, List.of(
                new DiscoveredProduct(RetailerKey.VIJAY_SALES, SAMSUNG_URL, SAMSUNG_TITLE,
                        SAMSUNG_SKU, "", "", "In stock", "", "INR"),
                new DiscoveredProduct(RetailerKey.VIJAY_SALES,
                        "https://www.vijaysales.com/p/258479/other-listing",
                        "Totally Unrelated Listing Title",
                        SAMSUNG_SKU, "", "", "In stock", "", "INR")));
        // Flipkart has no link; it can only match through the resolved title.
        ProductDiscoveryProvider fk = providerReturning(RetailerKey.FLIPKART, List.of(
                new DiscoveredProduct(RetailerKey.FLIPKART,
                        "https://www.flipkart.com/samsung-55/p/itmfk9",
                        "SAMSUNG 139 cm (55 inch) 4K Ultra HD Smart LED TV UA55",
                        "FKSKU2", "", "", "In stock", "", "INR")));

        MultiRetailerSearchService svc = service(vs, fk);
        when(productService.requireProduct(8L)).thenReturn(tv);
        when(linkRepository.findByProductId(8L)).thenReturn(List.of(vsLink));
        when(linkRepository.findByProductIdAndWebsite(8L, "Vijay Sales"))
                .thenReturn(Optional.of(vsLink));

        MultiRetailerSearchResponse r = svc.searchForProduct(8L);

        // Vijay Sales: both SKU-matched candidates pass the decisive SKU path.
        RetailerOfferSummary vsSummary = summaryFor(r, "Vijay Sales");
        assertEquals("MATCHED_CANDIDATES", vsSummary.status());
        assertEquals(2, vsSummary.offers().size());

        // Flipkart matched ONLY because the FIRST Vijay Sales candidate's
        // title (carrying the UA55 model code) won the title resolution.
        RetailerOfferSummary fkSummary = summaryFor(r, "Flipkart");
        assertEquals("MATCHED_CANDIDATES", fkSummary.status());
        assertEquals(1, fkSummary.offers().size());
        assertTrue(fkSummary.offers().get(0).title().contains("UA55"));
    }

    private static ProductDiscoveryProvider providerReturning(RetailerKey retailer,
                                                              List<DiscoveredProduct> candidates) {
        ProductDiscoveryProvider p = mock(ProductDiscoveryProvider.class);
        when(p.retailer()).thenReturn(retailer);
        try {
            when(p.discover(anyString())).thenReturn(candidates);
        } catch (Exception ignored) {
        }
        return p;
    }

    private static ProductDiscoveryProvider delayedProvider(RetailerKey retailer, long delayMillis) {
        ProductDiscoveryProvider p = mock(ProductDiscoveryProvider.class);
        when(p.retailer()).thenReturn(retailer);
        when(p.discover(anyString())).thenAnswer(invocation -> {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            String url = switch (retailer) {
                case VIJAY_SALES -> "https://www.vijaysales.com/p/ORD1/order-candidate";
                case RELIANCE_DIGITAL -> "https://www.reliancedigital.in/product/order-candidate-2";
                default -> "https://www.flipkart.com/order-candidate-3/p/itmord";
            };
            return List.of(new DiscoveredProduct(
                    retailer, url,
                    "Samsung 55 inch 4K Ultra HD Smart TV UA55",
                    "ORD-" + retailer, "", "", "In stock", "", "INR"));
        });
        return p;
    }

    private static ProductDiscoveryProvider concurrentProvider(RetailerKey retailer,
                                                               CountDownLatch bothInside) {
        ProductDiscoveryProvider p = mock(ProductDiscoveryProvider.class);
        when(p.retailer()).thenReturn(retailer);
        when(p.discover(anyString())).thenAnswer(invocation -> {
            // Each provider signals it is inside discover(); both must be
            // inside at the same time before either proceeds. A sequential
            // implementation would block until the timeout and then fail
            // loudly instead of silently passing.
            bothInside.countDown();
            if (!bothInside.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(retailer.getDisplayName()
                        + " never ran concurrently with the other provider");
            }
            String url = retailer == RetailerKey.VIJAY_SALES
                    ? "https://www.vijaysales.com/p/CONC1/concurrent-candidate"
                    : "https://www.reliancedigital.in/product/concurrent-candidate-1";
            return List.of(new DiscoveredProduct(
                    retailer, url,
                    "Samsung 55 inch 4K Ultra HD Smart TV UA55",
                    "CONC-" + retailer, "", "", "In stock", "", "INR"));
        });
        return p;
    }

    private static ProductDiscoveryProvider vsProvider(boolean includeCandidate) {
        ProductDiscoveryProvider p = mock(ProductDiscoveryProvider.class);
        when(p.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        if (includeCandidate) {
            try {
                when(p.discover(anyString())).thenReturn(List.of(vijayCandidate));
            } catch (Exception ignored) {
            }
        }
        return p;
    }
}