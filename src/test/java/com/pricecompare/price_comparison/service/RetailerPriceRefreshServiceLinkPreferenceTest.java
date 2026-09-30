package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.dto.PriceRefreshResponse;
import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.entity.ProductPrice;
import com.pricecompare.price_comparison.entity.RetailerLink;
import com.pricecompare.price_comparison.repository.ProductPriceRepository;
import com.pricecompare.price_comparison.repository.ProductRepository;
import com.pricecompare.price_comparison.repository.RetailerLinkRepository;
import com.pricecompare.price_comparison.retailer.ProductIdentityMatcher;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4: verifies that the EXISTING refresh pipeline uses the CONFIRMED
 * retailer link (not a stale/incorrect price-row URL), and that a confirmed
 * link's SKU activates the matcher's decisive SKU path at refresh time.
 * Pure unit tests (Mockito) — no network, no real retailer.
 */
class RetailerPriceRefreshServiceLinkPreferenceTest {

    private static final String SAMSUNG_URL =
            "https://www.vijaysales.com/p/258479/samsung-139-cm-55-inch-4k-ultra-hd-smart-tv";
    private static final String SAMSUNG_TITLE =
            "Samsung 139 cm (55 inch) 4K Ultra HD Smart LED TV UA55";
    private static final String SAMSUNG_SKU = "258479";
    private static final String LG_URL =
            "https://www.vijaysales.com/p/238476/lg-80-cm-32-inch-hd-ai-smart-webos-led-tv";
    private static final BigDecimal PRICE = new BigDecimal("16090");

    private final Product product = productWithId();

    private RetailerPriceProvider provider;
    private ProductPriceService priceService;

    /** The refresh pipeline keys every lookup off Product#id — set it explicitly. */
    private static Product productWithId() {
        Product product =
                new Product("Samsung 55 inch 4K Smart TV", "Samsung", "UA55", "Television");
        try {
            java.lang.reflect.Field id = Product.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(product, 1L);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return product;
    }

    private RetailerPriceRefreshService buildService(RetailerLink link) {
        ProductRepository productRepository = mock(ProductRepository.class);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        // Stale/incorrect price-row URL (the known Product-1 LG test data).
        ProductPriceRepository priceRepository = mock(ProductPriceRepository.class);
        when(priceRepository.findByProductId(1L)).thenReturn(List.of(
                new ProductPrice(product, "Vijay Sales", PRICE, LG_URL, LocalDateTime.now())));

        RetailerLinkRepository linkRepository = mock(RetailerLinkRepository.class);
        when(linkRepository.findByProductIdAndWebsite(anyLong(), anyString()))
                .thenReturn(Optional.ofNullable(link));

        provider = mock(RetailerPriceProvider.class);
        when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);

        RetailerRegistry registry = mock(RetailerRegistry.class);
        when(registry.allProviders()).thenReturn(List.of(provider));

        priceService = mock(ProductPriceService.class);

        return new RetailerPriceRefreshService(
                productRepository, priceRepository, registry, priceService,
                new ProductIdentityMatcher(), linkRepository);
    }

    /** The confirmed candidate URL — not the stale price-row URL — is fetched. */
    @Test
    void refreshUsesConfirmedLinkUrlInsteadOfStalePriceRowUrl() {
        RetailerLink link = new RetailerLink(product, "Vijay Sales", SAMSUNG_URL, SAMSUNG_SKU);
        RetailerPriceRefreshService service = buildService(link);

        when(provider.fetchPrice(anyString())).thenReturn(List.of(new RetailerPrice(
                RetailerKey.VIJAY_SALES, PRICE, SAMSUNG_URL, SAMSUNG_TITLE,
                LocalDateTime.now(), SAMSUNG_SKU)));

        PriceRefreshResponse response = service.refreshPricesForProduct(1L);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(provider).fetchPrice(urlCaptor.capture());
        assertEquals(SAMSUNG_URL, urlCaptor.getValue());

        // Persisted against the confirmed candidate URL and SKU.
        verify(priceService).addPrice(1L, "Vijay Sales", PRICE, SAMSUNG_URL, SAMSUNG_SKU);
        assertTrue(response.successfulProviders().contains("Vijay Sales"));
        assertEquals(1, response.pricesSaved());
    }

    /**
     * A confirmed link carries a trusted SKU: scraped SKU != link SKU is a
     * decisive mismatch (even with a title that would pass the title path).
     */
    @Test
    void confirmedLinkSkuActivatesDecisiveSkuPath() {
        RetailerLink link = new RetailerLink(product, "Vijay Sales", SAMSUNG_URL, SAMSUNG_SKU);
        RetailerPriceRefreshService service = buildService(link);

        // Samsung-looking title, but the scraped page SKU differs from the link.
        when(provider.fetchPrice(anyString())).thenReturn(List.of(new RetailerPrice(
                RetailerKey.VIJAY_SALES, PRICE, SAMSUNG_URL, SAMSUNG_TITLE,
                LocalDateTime.now(), "238476")));

        PriceRefreshResponse response = service.refreshPricesForProduct(1L);

        verify(priceService, never()).addPrice(anyLong(), anyString(), any(), anyString(), any());
        assertTrue(response.failedProviders().contains("Vijay Sales"));
        assertEquals(0, response.pricesSaved());
        String reason = response.failureReasons().get("Vijay Sales");
        assertTrue(reason != null && reason.contains("SKU"), "got: " + reason);
    }
}

