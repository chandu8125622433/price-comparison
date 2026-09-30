package com.pricecompare.price_comparison.retailer.discovery;

import com.pricecompare.price_comparison.retailer.RetailerConfigurationException;
import com.pricecompare.price_comparison.retailer.RetailerException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic unit tests for the Phase 7 Flipkart discovery provider:
 * missing credentials, payload normalization, empty results, malformed
 * payloads and product-URL validation. No network, no credentials needed.
 */
class FlipkartDiscoveryProviderTest {

    private final FlipkartDiscoveryProvider unconfigured =
            new FlipkartDiscoveryProvider("", "", 5);

    /* 1. Missing credentials -> explicit CONFIGURATION_REQUIRED signal. */
    @Test
    void missingCredentialsFailWithConfigurationRequired() {
        RetailerConfigurationException ex = assertThrows(
                RetailerConfigurationException.class,
                () -> unconfigured.discover("Apple iPhone 16"));
        assertTrue(ex.getMessage().contains("credentials are not configured"));
    }

    /* 2. Affiliate payload normalization into the common model. */
    @Test
    void parsesAffiliateSearchPayload() {
        String json = """
                {"productInfoList":[
                  {"productBaseInfoV1":{
                     "productId":"MOBXXXXYYYY",
                     "title":"Apple iPhone 16 (128 GB, Teal)",
                     "productUrl":"http://www.flipkart.com/apple-iphone-16/p/itmb?pid=MOBXXXXYYYY",
                     "imageUrls":["http://rukminim.flipkart.com/img/1.jpg"],
                     "availability":"available",
                     "pricing":{"finalPrice":68990,"mrp":79900}
                  }},
                  {"productBaseInfoV1":{
                     "productId":"MOBZZZZ",
                     "title":"Apple iPhone 16 Plus (128 GB, Black)",
                     "productUrl":"http://www.flipkart.com/apple-iphone-16-plus/p/itmb?pid=MOBZZZZ",
                     "availability":"unavailable"
                  }}
                ]}
                """;
        List<DiscoveredProduct> out = unconfigured.parseResponse(json, 5);

        assertEquals(2, out.size());
        DiscoveredProduct first = out.get(0);
        assertEquals(RetailerKey.FLIPKART, first.retailer());
        assertEquals("MOBXXXXYYYY", first.sku());
        assertEquals("Apple iPhone 16 (128 GB, Teal)", first.productTitle());
        assertEquals("68990", first.price());
        assertEquals("79900", first.mrp());
        assertEquals("INR", first.currency());
        assertEquals("In stock", first.availability());
        assertTrue(first.productUrl().contains("flipkart.com"));
        assertTrue(first.imageUrl().contains("rukminim"));
        assertEquals("Out of stock", out.get(1).availability());
        assertEquals("", out.get(1).price()); // absent pricing -> never invented
    }

    /* 3. Empty/absent result set -> empty candidate list, not an error. */
    @Test
    void emptyResultYieldsNoCandidates() {
        assertEquals(0, unconfigured.parseResponse("{\"productInfoList\":[]}", 5).size());
        assertEquals(0, unconfigured.parseResponse("{}", 5).size());
    }

    /* 4. Malformed payload -> controlled RetailerException (no crash). */
    @Test
    void malformedPayloadFailsGracefully() {
        assertThrows(RetailerException.class,
                () -> unconfigured.parseResponse("{not-json", 5));
    }

    /* 5. URL validation: product pages only, foreign hosts rejected. */
    @Test
    void validatesFlipkartProductUrls() {
        assertTrue(FlipkartDiscoveryProvider.isTrackableProductUrl(
                "https://www.flipkart.com/apple-iphone-16/p/itmb?pid=MOBXXXXYYYY"));
        assertTrue(FlipkartDiscoveryProvider.isTrackableProductUrl(
                "http://www.flipkart.com/some-product/p/itm123"));
        assertEquals(false, FlipkartDiscoveryProvider.isTrackableProductUrl(
                "https://www.amazon.in/apple-iphone-16/p/itmb"));
        assertEquals(false, FlipkartDiscoveryProvider.isTrackableProductUrl(
                "https://www.flipkart.com/offers-list"));
        assertEquals(false, FlipkartDiscoveryProvider.isTrackableProductUrl(
                "javascript:alert(1)"));
        assertEquals(false, FlipkartDiscoveryProvider.isTrackableProductUrl(null));
    }
}
