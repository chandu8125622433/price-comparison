package com.pricecompare.price_comparison.retailer.provider;

import com.pricecompare.price_comparison.retailer.RetailerConfigurationException;
import com.pricecompare.price_comparison.retailer.RetailerException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic unit tests for the Phase 7 Flipkart price provider:
 * credential gating, URL/pid validation and Affiliate payload normalization.
 * No network, no credentials needed.
 */
class FlipkartRetailerPriceProviderTest {

    private final FlipkartRetailerPriceProvider unconfigured =
            new FlipkartRetailerPriceProvider("", "");

    private static final String PDP_URL =
            "https://www.flipkart.com/apple-iphone-16/p/itmb?pid=MOBXXXXYYYY";

    /* 1. Missing credentials -> controlled configuration-required failure. */
    @Test
    void missingCredentialsFailGracefully() {
        RetailerConfigurationException ex = assertThrows(
                RetailerConfigurationException.class,
                () -> unconfigured.fetchPrice(PDP_URL));
        assertTrue(ex.getMessage().contains("credentials are not configured"));
    }

    /* 2. Non-Flipkart URLs are refused even before any network call. */
    @Test
    void foreignUrlsAreRefused() {
        // Configured instance so the failure is clearly the URL check.
        FlipkartRetailerPriceProvider configured =
                new FlipkartRetailerPriceProvider("id", "token");
        RetailerException ex = assertThrows(RetailerException.class,
                () -> configured.fetchPrice("https://www.vijaysales.com/p/238476/lg-tv"));
        assertTrue(ex.getMessage().contains("not a valid Flipkart product page"));
    }

    /* 3. pid extraction (the Affiliate product identifier). */
    @Test
    void extractsPidFromProductUrl() {
        assertEquals("MOBXXXXYYYY", FlipkartRetailerPriceProvider.extractPid(PDP_URL));
        assertEquals("", FlipkartRetailerPriceProvider.extractPid(
                "https://www.flipkart.com/apple-iphone-16/p/itmb"));
    }

    /* 4. Affiliate product payload normalization. */
    @Test
    void parsesAffiliateProductPayload() {
        FlipkartRetailerPriceProvider configured =
                new FlipkartRetailerPriceProvider("id", "token");
        String json = """
                {"productBaseInfoV1":{
                   "productId":"MOBXXXXYYYY",
                   "title":"Apple iPhone 16 (128 GB, Teal)",
                   "productUrl":"http://www.flipkart.com/apple-iphone-16/p/itmb?pid=MOBXXXXYYYY",
                   "pricing":{"finalPrice":68990,"mrp":79900}
                }}
                """;
        List<com.pricecompare.price_comparison.retailer.RetailerPrice> out =
                configured.parseResponse(json, PDP_URL);

        assertEquals(1, out.size());
        assertEquals(RetailerKey.FLIPKART, out.get(0).retailer());
        assertEquals(new BigDecimal("68990"), out.get(0).price());
        assertEquals("MOBXXXXYYYY", out.get(0).sku());
        assertEquals("Apple iPhone 16 (128 GB, Teal)", out.get(0).productTitle());
        assertTrue(out.get(0).productUrl().contains("flipkart.com"));
    }

    /* 5. Payload without a usable price -> controlled failure, no fake price. */
    @Test
    void payloadWithoutPriceFailsGracefully() {
        FlipkartRetailerPriceProvider configured =
                new FlipkartRetailerPriceProvider("id", "token");
        String json = """
                {"productBaseInfoV1":{"productId":"MOB1","title":"Apple iPhone 16"}}
                """;
        RetailerException ex = assertThrows(RetailerException.class,
                () -> configured.parseResponse(json, PDP_URL));
        assertTrue(ex.getMessage().contains("no usable selling price"));
    }

    /* 6. Amount parsing never crashes on junk. */
    @Test
    void amountParsingHandlesJunk() {
        assertEquals(new BigDecimal("68990"), FlipkartRetailerPriceProvider.parseAmount("68990"));
        assertEquals(new BigDecimal("68990"), FlipkartRetailerPriceProvider.parseAmount("₹68,990"));
        assertNull(FlipkartRetailerPriceProvider.parseAmount(null));
        assertNull(FlipkartRetailerPriceProvider.parseAmount("null"));
        assertNull(FlipkartRetailerPriceProvider.parseAmount("-"));
        assertNull(FlipkartRetailerPriceProvider.parseAmount("N/A"));
        assertNull(FlipkartRetailerPriceProvider.parseAmount("0"));
    }
}
