package com.pricecompare.price_comparison.retailer.provider;

import com.pricecompare.price_comparison.retailer.RetailerException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Deterministic, network-free unit tests for the hardened Vijay Sales price
 * parsing. Verifies that non-priced values (the literal "null" offer marker,
 * blank, "-", "₹"-only, malformed and non-numeric text, non-positive values)
 * all surface as a controlled {@link RetailerException} ("price unavailable")
 * rather than a BigDecimal parsing crash.
 */
class VijaySalesRetailerPriceProviderParsingTest {

    @Test
    void parsesPlainSellingPrice() {
        assertEquals(new BigDecimal("16090"), VijaySalesRetailerPriceProvider.parsePrice("16090"));
    }

    @Test
    void parsesFormattedSellingPrices() {
        assertEquals(new BigDecimal("16090"), VijaySalesRetailerPriceProvider.parsePrice("₹16,090"));
        assertEquals(new BigDecimal("23990.50"), VijaySalesRetailerPriceProvider.parsePrice("₹ 23990.50"));
        assertEquals(new BigDecimal("96990"), VijaySalesRetailerPriceProvider.parsePrice(" 96990 "));
    }

    @Test
    void neverParsesLiteralNullOfferMarker() {
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice("null"));
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice("NULL"));
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice(" null "));
    }

    @Test
    void rejectsBlankAndMissing() {
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice(null));
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice(""));
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice("   "));
    }

    @Test
    void rejectsDashAndCurrencyPlaceholders() {
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice("-"));
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice("—"));
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice("₹"));
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice("N/A"));
    }

    @Test
    void rejectsMalformedAndNonNumeric() {
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice("abc"));
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice("not-a-price"));
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice("1.2.3"));
    }

    @Test
    void rejectsNonPositive() {
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice("0"));
        assertThrows(RetailerException.class, () -> VijaySalesRetailerPriceProvider.parsePrice("-16090"));
    }
}