package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.dto.PriceRefreshResponse;
import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.entity.ProductPrice;
import com.pricecompare.price_comparison.repository.ProductPriceRepository;
import com.pricecompare.price_comparison.repository.ProductRepository;
import com.pricecompare.price_comparison.repository.RetailerLinkRepository;
import com.pricecompare.price_comparison.retailer.ProductIdentityMatcher;
import com.pricecompare.price_comparison.retailer.RetailerConfigurationException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.retailer.RetailerPrice;
import com.pricecompare.price_comparison.retailer.RetailerPriceProvider;
import com.pricecompare.price_comparison.retailer.RetailerRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7: a Flipkart failure (e.g. missing credentials) must NEVER prevent
 * Vijay Sales from refreshing, and must never persist a fabricated Flipkart
 * price. Pure unit test (Mockito) — no network, no real retailers.
 */
class RetailerPriceRefreshServiceFlipkartIsolationTest {

    private static final String SAMSUNG_URL =
            "https://www.vijaysales.com/p/258479/samsung-139-cm-55-inch-4k-ultra-hd-smart-tv";
    private static final String SAMSUNG_TITLE =
            "Samsung 139 cm (55 inch) 4K Ultra HD Smart LED TV UA55";
    private static final String SAMSUNG_SKU = "258479";
    private static final BigDecimal PRICE = new BigDecimal("16090");

    private final ProductPriceService priceService = mock(ProductPriceService.class);

    private RetailerPriceRefreshService buildService(RetailerPriceProvider vijayProvider,
                                                     RetailerPriceProvider flipkartProvider) {
        Product product =
                new Product("Samsung 55 inch 4K Smart TV", "Samsung", "UA55", "Television");
        try {
            java.lang.reflect.Field id = Product.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(product, 1L);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }

        ProductRepository productRepository = mock(ProductRepository.class);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductPriceRepository priceRepository = mock(ProductPriceRepository.class);
        when(priceRepository.findByProductId(anyLong())).thenReturn(List.of(
                new ProductPrice(product, "Vijay Sales", PRICE, SAMSUNG_URL, LocalDateTime.now())));

        RetailerLinkRepository linkRepository = mock(RetailerLinkRepository.class);
        when(linkRepository.findByProductIdAndWebsite(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        RetailerRegistry registry = mock(RetailerRegistry.class);
        when(registry.allProviders()).thenReturn(List.of(vijayProvider, flipkartProvider));

        return new RetailerPriceRefreshService(
                productRepository, priceRepository, registry, priceService,
                new ProductIdentityMatcher(), linkRepository);
    }

    @Test
    void flipkartConfigurationFailureDoesNotBlockVijaySales() {
        RetailerPriceProvider vijay = mock(RetailerPriceProvider.class);
        when(vijay.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        when(vijay.fetchPrice(anyString())).thenReturn(List.of(new RetailerPrice(
                RetailerKey.VIJAY_SALES, PRICE, SAMSUNG_URL, SAMSUNG_TITLE,
                LocalDateTime.now(), SAMSUNG_SKU)));

        RetailerPriceProvider flipkart = mock(RetailerPriceProvider.class);
        when(flipkart.retailer()).thenReturn(RetailerKey.FLIPKART);
        when(flipkart.fetchPrice(anyString())).thenThrow(
                new RetailerConfigurationException(
                        "Flipkart Affiliate API credentials are not configured."));

        RetailerPriceRefreshService service =
                buildService(vijay, flipkart);

        PriceRefreshResponse response = service.refreshPricesForProduct(1L);

        assertTrue(response.successfulProviders().contains("Vijay Sales"));
        assertEquals(1, response.pricesSaved());
        assertTrue(response.failedProviders().contains("Flipkart"));
        assertTrue(response.failureReasons().get("Flipkart").contains("credentials"));

        // Exactly ONE persisted price — the real Vijay Sales one. No fake
        // Flipkart price may ever reach ProductPrice.
        ArgumentCaptor<String> websiteCaptor = ArgumentCaptor.forClass(String.class);
        verify(priceService, times(1)).addPrice(anyLong(), websiteCaptor.capture(),
                any(), anyString(), any());
        assertEquals("Vijay Sales", websiteCaptor.getValue());
    }
}
