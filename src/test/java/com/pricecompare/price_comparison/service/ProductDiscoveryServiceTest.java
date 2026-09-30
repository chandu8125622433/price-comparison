package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.dto.ProductDiscoveryResponse;
import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.exception.ResourceNotFoundException;
import com.pricecompare.price_comparison.retailer.RetailerException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.retailer.discovery.DiscoveredProduct;
import com.pricecompare.price_comparison.retailer.discovery.ProductDiscoveryProvider;
import com.pricecompare.price_comparison.retailer.discovery.ProductDiscoveryRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Deterministic unit tests for the discovery coordinator: candidate
 * collection, cross-provider de-duplication, limits, failure isolation and
 * empty handling. Uses stub providers — no network, no real retailers.
 */
class ProductDiscoveryServiceTest {

    private static final String VS_TV_URL =
            "https://www.vijaysales.com/p/238476/lg-32-inch-hd-smart-tv";
    private static final String VS_TV_URL_2 =
            "https://www.vijaysales.com/p/245426/xiaomi-32-inch-hd-google-tv";

    private static DiscoveredProduct product(RetailerKey retailer, String url, String title) {
        return new DiscoveredProduct(retailer, url, title, "238476", "", "20990", "In stock");
    }

    /** Test-double discovery provider; result==null simulates a provider failure. */
    private static ProductDiscoveryProvider provider(RetailerKey key,
                                                     List<DiscoveredProduct> result,
                                                     AtomicInteger calls) {
        return new ProductDiscoveryProvider() {
            @Override
            public RetailerKey retailer() {
                return key;
            }

            @Override
            public List<DiscoveredProduct> discover(String query) {
                calls.incrementAndGet();
                if (result == null) {
                    throw new RetailerException("search endpoint unavailable");
                }
                return result;
            }
        };
    }

    private static Product product() {
        Product product = new Product(
                "Samsung 55 inch 4K Smart TV", "Samsung", "UA55", "Television");
        try {
            Field id = Product.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(product, 1L);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return product;
    }

    private static ProductDiscoveryService service(ProductService productService,
                                                   int maxTotalCandidates,
                                                   ProductDiscoveryProvider... providers) {
        return new ProductDiscoveryService(
                productService,
                new ProductDiscoveryRegistry(List.of(providers)),
                maxTotalCandidates);
    }

    private static ProductService productRepositoryReturning(Product product) {
        ProductService productService = mock(ProductService.class);
        when(productService.requireProduct(1L)).thenReturn(product);
        return productService;
    }

    @Test
    void providerReturnsCandidates() {
        AtomicInteger calls = new AtomicInteger();
        ProductDiscoveryService service = service(
                productRepositoryReturning(product()),
                10,
                provider(RetailerKey.VIJAY_SALES,
                        List.of(product(RetailerKey.VIJAY_SALES, VS_TV_URL, "LG 32 inch TV"),
                                product(RetailerKey.VIJAY_SALES, VS_TV_URL_2, "Xiaomi 32 inch TV")),
                        calls));

        ProductDiscoveryResponse response = service.discoverForProduct(1L);

        assertEquals(2, response.candidates().size());
        assertEquals("Vijay Sales", response.candidates().get(0).retailer());
        assertEquals("LG 32 inch TV", response.candidates().get(0).productTitle());
        assertEquals("238476", response.candidates().get(0).sku());
        assertEquals(VS_TV_URL, response.candidates().get(0).productUrl());
        assertEquals("In stock", response.candidates().get(0).availability());
        assertEquals(1, response.providersAttempted());
        assertEquals("Samsung 55 inch 4K Smart TV", response.productName());
        assertEquals("Samsung 55 inch 4K Smart TV", response.searchQuery());
        assertTrue(response.failedProviders().isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    void duplicateUrlsAreRemovedAcrossProviders() {
        AtomicInteger calls = new AtomicInteger();
        ProductDiscoveryService service = service(
                productRepositoryReturning(product()),
                10,
                provider(RetailerKey.VIJAY_SALES,
                        List.of(product(RetailerKey.VIJAY_SALES, VS_TV_URL, "LG 32 inch TV")),
                        calls),
                provider(RetailerKey.AMAZON,
                        List.of(product(RetailerKey.AMAZON, VS_TV_URL + "/", "Same TV")),
                        calls));

        ProductDiscoveryResponse response = service.discoverForProduct(1L);

        assertEquals(1, response.candidates().size());
        assertEquals("Vijay Sales", response.candidates().get(0).retailer());
    }

    @Test
    void overallCandidateLimitIsEnforced() {
        ProductDiscoveryService service = service(
                productRepositoryReturning(product()),
                1,
                provider(RetailerKey.VIJAY_SALES,
                        List.of(product(RetailerKey.VIJAY_SALES, VS_TV_URL, "LG 32 inch TV"),
                                product(RetailerKey.VIJAY_SALES, VS_TV_URL_2, "Xiaomi 32 inch TV")),
                        new AtomicInteger()));

        assertEquals(1, service.discoverForProduct(1L).candidates().size());
    }

    @Test
    void providerFailureDoesNotCrashDiscovery() {
        ProductDiscoveryService service = service(
                productRepositoryReturning(product()),
                10,
                provider(RetailerKey.VIJAY_SALES, null, new AtomicInteger()),
                provider(RetailerKey.AMAZON,
                        List.of(product(RetailerKey.AMAZON,
                                "https://www.amazon.in/dp/ABC123", "Same TV")),
                        new AtomicInteger()));

        ProductDiscoveryResponse response = service.discoverForProduct(1L);

        assertEquals(1, response.candidates().size());
        assertTrue(response.failedProviders().contains("Vijay Sales"));
        assertTrue(response.failureReasons()
                .get("Vijay Sales")
                .contains("search endpoint unavailable"));
    }

    @Test
    void emptyResultIsHandledCorrectly() {
        ProductDiscoveryService service = service(
                productRepositoryReturning(product()),
                10,
                provider(RetailerKey.VIJAY_SALES, List.of(), new AtomicInteger()));

        ProductDiscoveryResponse response = service.discoverForProduct(1L);

        assertTrue(response.candidates().isEmpty());
        assertEquals(1, response.providersAttempted());
        assertTrue(response.failedProviders().isEmpty());
    }

    @Test
    void unknownProductPropagatesControlledError() {
        ProductService productService = mock(ProductService.class);
        when(productService.requireProduct(99L))
                .thenThrow(new ResourceNotFoundException("Product not found with id: 99"));
        ProductDiscoveryService service = service(productService, 10,
                provider(RetailerKey.VIJAY_SALES, List.of(), new AtomicInteger()));

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.discoverForProduct(99L));
        assertTrue(ex.getMessage().contains("99"));
    }

    @Test
    void productWithoutSearchableIdentitySkipsProviders() {
        AtomicInteger calls = new AtomicInteger();
        Product blank = new Product("  ", "", null, null);
        ProductDiscoveryService service = service(
                productRepositoryReturning(blank), 10,
                provider(RetailerKey.VIJAY_SALES,
                        List.of(product(RetailerKey.VIJAY_SALES, VS_TV_URL, "LG 32 inch TV")),
                        calls));

        ProductDiscoveryResponse response = service.discoverForProduct(1L);

        assertTrue(response.candidates().isEmpty());
        assertEquals(0, response.providersAttempted());
        assertEquals(0, calls.get());
    }
}
