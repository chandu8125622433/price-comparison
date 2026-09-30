package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.dto.PriceRefreshResponse;
import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.entity.ProductPrice;
import com.pricecompare.price_comparison.repository.ProductPriceRepository;
import com.pricecompare.price_comparison.repository.ProductRepository;
import com.pricecompare.price_comparison.repository.RetailerLinkRepository;
import com.pricecompare.price_comparison.retailer.ProductIdentityMatcher;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.retailer.RetailerPrice;
import com.pricecompare.price_comparison.retailer.RetailerPriceProvider;
import com.pricecompare.price_comparison.retailer.RetailerRegistry;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that the Phase 2 identity guard is enforced at the refresh-service
 * level, directly against the {@link ProductPriceService} mock that is injected
 * into {@link RetailerPriceRefreshService}:
 *
 * <ul>
 *   <li>mismatch (requested Samsung, scraped LG) -> {@code addPrice} never called,
 *       provider reported failed with a clear mismatch reason;</li>
 *   <li>match (requested Samsung, scraped Samsung) -> {@code addPrice} called once
 *       and the provider reported successful.</li>
 * </ul>
 *
 * Pure unit tests (Mockito), DB/network-free. Each test creates its own
 * {@code ProductPriceService} mock, passes it through {@code buildService}
 * (which injects exactly that instance), and verifies that same instance —
 * so interactions can never leak between tests.
 */
class RetailerPriceRefreshServiceIdentityTest {

    private static final String SAMSUNG_NAME = "Samsung 55 inch 4K Smart TV";
    private static final String LG_TITLE = "LG 80 cm (32 Inch) HD AI Smart WebOS LED TV";
    private static final String VS_URL =
            "https://www.vijaysales.com/p/238476/lg-80-cm-32-inch-hd-ai-smart-webos-led-tv";
    private static final BigDecimal PRICE = new BigDecimal("16090");

    private RetailerPriceRefreshService buildService(ProductPriceService priceService,
                                                     RetailerPrice scraped) {
        Product product = new Product(SAMSUNG_NAME, "Samsung", "UA55", "Television");
        setId(product, 1L);

        ProductRepository productRepository = mock(ProductRepository.class);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductPriceRepository priceRepository = mock(ProductPriceRepository.class);
        when(priceRepository.findByProductId(anyLong())).thenReturn(List.of(
                new ProductPrice(product, "Vijay Sales", PRICE, VS_URL, LocalDateTime.now())));

        // No confirmed retailer link in these tests: expectedSku resolves to ""
        // so the Phase 2 title path applies (exactly the pre-Phase-4 behavior).
        RetailerLinkRepository linkRepository = mock(RetailerLinkRepository.class);
        when(linkRepository.findByProductIdAndWebsite(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        RetailerPriceProvider provider = mock(RetailerPriceProvider.class);
        when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        when(provider.fetchPrice(anyString())).thenReturn(List.of(scraped));

        RetailerRegistry registry = mock(RetailerRegistry.class);
        when(registry.allProviders()).thenReturn(List.of(provider));

        return new RetailerPriceRefreshService(
                productRepository, priceRepository, registry, priceService,
                new ProductIdentityMatcher(), linkRepository);
    }

    private static RetailerPrice scraped(String title, String sku) {
        return new RetailerPrice(
                RetailerKey.VIJAY_SALES, PRICE, VS_URL, title, LocalDateTime.now(), sku);
    }

    private static void setId(Product product, Long id) {
        try {
            java.lang.reflect.Field field = Product.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(product, id);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot set Product id in test", ex);
        }
    }

    /** Inverse: '{@code LG}' page (wrong stored URL) -> must never be saved. */
    @Test
    void mismatchDoesNotPersistAndIsReported() {
        ProductPriceService priceService = mock(ProductPriceService.class);
        RetailerPriceRefreshService service =
                buildService(priceService, scraped(LG_TITLE, "238476"));

        PriceRefreshResponse response = service.refreshPricesForProduct(1L);

        verify(priceService, never()).addPrice(anyLong(), anyString(), any(), anyString(), any());

        assertTrue(response.failedProviders().contains("Vijay Sales"));
        assertTrue(response.successfulProviders().isEmpty());
        assertEquals(0, response.pricesSaved());
        String reason = response.failureReasons().get("Vijay Sales");
        assertTrue(reason != null && reason.contains("mismatch"), "expected mismatch reason, got: " + reason);
        assertTrue(reason.contains(SAMSUNG_NAME));
    }

    /** Matching retailer page (correct identity) must still be persisted. */
    @Test
    void matchingProductIsPersisted() {
        ProductPriceService priceService = mock(ProductPriceService.class);
        RetailerPriceRefreshService service =
                buildService(priceService, scraped(SAMSUNG_NAME, "238476"));

        PriceRefreshResponse response = service.refreshPricesForProduct(1L);

        verify(priceService).addPrice(1L, "Vijay Sales", PRICE, VS_URL, "238476");

        assertTrue(response.successfulProviders().contains("Vijay Sales"));
        assertTrue(response.failedProviders().isEmpty());
        assertEquals(1, response.pricesSaved());
    }
}