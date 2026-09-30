package com.pricecompare.price_comparison.retailer;

import com.pricecompare.price_comparison.entity.Product;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static com.pricecompare.price_comparison.retailer.ProductIdentityMatcher.Outcome.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the Phase 2 {@link ProductIdentityMatcher} identity guard.
 * All tests are deterministic and DB/network-free.
 */
class ProductIdentityMatcherTest {

    private final ProductIdentityMatcher matcher = new ProductIdentityMatcher();

    private static Product samsung55() {
        return new Product("Samsung 55 inch 4K Smart TV", "Samsung", "UA55", "Television");
    }

    private static RetailerPrice scrape(String title, String sku) {
        return new RetailerPrice(
                RetailerKey.VIJAY_SALES,
                new BigDecimal("16090"),
                "https://www.vijaysales.com/p/238476/lg-80-cm-32-inch",
                title,
                LocalDateTime.now(),
                sku
        );
    }

    @Test
    void exactSkuMatchIsAccepted() {
        ProductIdentityMatcher.Decision d =
                matcher.evaluate("238476", samsung55(), scrape("LG 32 inch HD TV", "238476"));
        assertEquals(MATCH, d.outcome());
    }

    @Test
    void skuMismatchIsRejected() {
        ProductIdentityMatcher.Decision d =
                matcher.evaluate("999999", samsung55(), scrape("LG 32 inch HD TV", "238476"));
        assertEquals(MISMATCH, d.outcome());
        assertEquals("Retailer product mismatch: SKU expected '999999' but scraped '238476'.",
                d.reason());
    }

    /* ---- Model-strict evaluation against the user-confirmed identity ---- */

    private static Product lgAc() {
        return new Product("LG 1.5 Ton 5 Star Split AC", "LG", "LS-Q18YNZA", "AC");
    }

    private static RetailerPrice rdScrape(String title, String sku) {
        return new RetailerPrice(
                RetailerKey.RELIANCE_DIGITAL,
                new BigDecimal("45490"),
                "https://www.reliancedigital.in/product/lg-1-5-ton-5-star-inverter-split-ac-as-q20enze-9000002",
                title,
                LocalDateTime.now(),
                sku
        );
    }

    @Test
    void confirmedTitleExactModelIsAccepted() {
        ProductIdentityMatcher.Decision d = matcher.evaluateConfirmed("",
                "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)",
                lgAc(),
                rdScrape("LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE, White)", "9000002"));
        assertEquals(MATCH, d.outcome());
    }

    @Test
    void confirmedTitleRejectsDifferentModelQ19C() {
        ProductIdentityMatcher.Decision d = matcher.evaluateConfirmed("",
                "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)",
                lgAc(),
                rdScrape("LG 1.5 Ton 5 Star Inverter Split AC (AS-Q19CNZE1, White)", "9000001"));
        assertEquals(MISMATCH, d.outcome());
        assertTrue(d.reason().contains("model-strict"));
    }

    @Test
    void confirmedTitleRejectsDifferentModelQ19W() {
        ProductIdentityMatcher.Decision d = matcher.evaluateConfirmed("",
                "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)",
                lgAc(),
                rdScrape("LG 1.5 Ton 5 Star Inverter Split AC (AS-Q19CWZE1)", "9000003"));
        assertEquals(MISMATCH, d.outcome());
    }

    @Test
    void confirmedTitleRejectsDifferentSeries() {
        ProductIdentityMatcher.Decision d = matcher.evaluateConfirmed("",
                "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)",
                lgAc(),
                rdScrape("LG 1.5 Ton 5 Star Inverter Split AC (LS-Q18MNZA)", "9000004"));
        assertEquals(MISMATCH, d.outcome());
    }

    @Test
    void confirmedSkuIsDecisiveEvenWhenTitleMatches() {
        ProductIdentityMatcher.Decision d = matcher.evaluateConfirmed("256812",
                "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)",
                lgAc(),
                rdScrape("LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)", "999999"));
        assertEquals(MISMATCH, d.outcome());
    }

    @Test
    void titleMatchWhenSkuUnavailable() {
        // No expected SKU and no scraped SKU -> normalized title containment.
        ProductIdentityMatcher.Decision d = matcher.evaluate(samsung55(),
                scrape("  Samsung  55 INCH 4K ULTRA HD SMART TV (UA55)  ", ""));
        assertEquals(MATCH, d.outcome());
    }

    @Test
    void titleMismatchDifferentBrand() {
        // The exact Samsung->LG bug: same URL now points at an LG TV.
        ProductIdentityMatcher.Decision d = matcher.evaluate(samsung55(),
                scrape("LG 80 cm (32 Inch) HD AI Smart WebOS LED TV", "238476"));
        assertEquals(MISMATCH, d.outcome());
        assertEquals("Retailer product mismatch: requested Samsung 55 inch 4K Smart TV"
                + " but scraped LG 80 cm (32 Inch) HD AI Smart WebOS LED TV.", d.reason());
    }

    @Test
    void titleMismatchSameBrandDifferentModel() {
        // Same brand but a clearly different size must NOT be accepted.
        ProductIdentityMatcher.Decision d = matcher.evaluate(
                new Product("Samsung 65 inch 4K QLED TV", "Samsung", "QA65", "Television"),
                scrape("Samsung 43 inch 4K Smart LED TV", ""));
        assertEquals(MISMATCH, d.outcome());
    }

    @Test
    void missingIdentityDataFailsClosed() {
        // No scraped title and no scraped SKU -> IDENTITY_UNAVAILABLE, never a match.
        ProductIdentityMatcher.Decision d = matcher.evaluate(samsung55(), scrape("", ""));
        assertEquals(IDENTITY_UNAVAILABLE, d.outcome());
    }

    @Test
    void requestWithoutAnyIdentityTokensFailsClosed() {
        Product unnamed = new Product("", "", "", "");
        ProductIdentityMatcher.Decision d = matcher.evaluate(unnamed, scrape("Some product", ""));
        assertEquals(IDENTITY_UNAVAILABLE, d.outcome());
    }

    /* ---------- Phase 7: product-family conflict guard ---------- */

    private static Product macbookAirM3() {
        return new Product("Apple MacBook Air M3", "Apple", "MacBook Air M3", "Laptop");
    }

    private static RetailerPrice flipkartScrape(String title, String sku) {
        return new RetailerPrice(
                RetailerKey.FLIPKART,
                new BigDecimal("114990"),
                "https://www.flipkart.com/p/itmb?pid=" + sku,
                title,
                LocalDateTime.now(),
                sku
        );
    }

    /** Phase 6 live finding: iPad Air M3 must NOT match MacBook Air M3. */
    @Test
    void rejectsIpadAirM3ForMacbookAirM3() {
        ProductIdentityMatcher.Decision d = matcher.evaluate("", macbookAirM3(),
                flipkartScrape("APPLE IPAD AIR M3 2025 13 WIFI 256GB STARLIGHT", "238533"));
        assertEquals(MISMATCH, d.outcome());
        assertTrue(d.reason().contains("family"), "expected family-conflict reason: " + d.reason());
    }

    /** The same product family with matching anchors must still be accepted. */
    @Test
    void stillMatchesSameFamilyTitle() {
        ProductIdentityMatcher.Decision d = matcher.evaluate("", macbookAirM3(),
                flipkartScrape("Apple MacBook Air M3 13 inch 8GB 256GB Space Grey", ""));
        assertEquals(MATCH, d.outcome());
    }
}