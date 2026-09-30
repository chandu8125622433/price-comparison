package com.pricecompare.price_comparison.retailer.provider;

import com.pricecompare.price_comparison.retailer.RetailerException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Focused, network-free tests for the Reliance Digital price provider:
 * retailer key, URL safety validation and controlled rejections. The URL
 * rules run before any network access, so these tests never touch the web.
 */
class RelianceDigitalRetailerPriceProviderTest {

    private final RelianceDigitalRetailerPriceProvider provider =
            new RelianceDigitalRetailerPriceProvider();

    @Test
    void retailerKeyIsRelianceDigital() {
        assertEquals(RetailerKey.RELIANCE_DIGITAL, provider.retailer());
    }

    @Test
    void fetchPriceRejectsBlankUrl() {
        assertThrows(RetailerException.class, () -> provider.fetchPrice(" "));
        assertThrows(RetailerException.class, () -> provider.fetchPrice(null));
    }

    @Test
    void fetchPriceRejectsForeignHost() {
        assertThrows(RetailerException.class, () -> provider.fetchPrice(
                "https://www.example.com/product/lg-ac-9000002"));
    }

    @Test
    void fetchPriceRejectsQueryStringUrl() {
        // robots.txt disallows query-string URLs: the provider refuses them.
        assertThrows(RetailerException.class, () -> provider.fetchPrice(
                "https://www.reliancedigital.in/product/lg-ac-9000002?searchQuery=x"));
    }

    @Test
    void fetchPriceRejectsNonProductPath() {
        assertThrows(RetailerException.class, () -> provider.fetchPrice(
                "https://www.reliancedigital.in/c/air-conditioners"));
    }

    @Test
    void fetchPriceRejectsMalformedUrl() {
        assertThrows(RetailerException.class, () -> provider.fetchPrice(
                "htp:/not-a-url"));
    }
}