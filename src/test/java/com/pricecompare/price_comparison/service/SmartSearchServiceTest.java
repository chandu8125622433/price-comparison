package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.dto.SmartSearchResponse;
import com.pricecompare.price_comparison.dto.CategoryListingResponse;
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
import com.pricecompare.price_comparison.retailer.discovery.DiscoveredProduct;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cache-first search decision logic (Phase "cache-first + live discovery on
 * miss"). All mocked â€” no database and no live retailer calls.
 */
class SmartSearchServiceTest {

    private static final String Q20_URL =
            "https://www.vijaysales.com/p/256812/lg-1-5-ton-5-star-inverter-split-ac-as-q20enze";
    private static final String Q20_TITLE =
            "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)";
    private static final String Q19_URL =
            "https://www.vijaysales.com/p/9000001/lg-1-5-ton-5-star-inverter-split-ac-as-q19cnze1";
    private static final String Q19_TITLE =
            "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q19CNZE1)";

    private final ProductService productService = mock(ProductService.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductPriceRepository productPriceRepository = mock(ProductPriceRepository.class);
    private final ProductDiscoveryService discoveryService = mock(ProductDiscoveryService.class);
    private final com.pricecompare.price_comparison.retailer.RetailerRegistry retailerRegistry =
            mock(com.pricecompare.price_comparison.retailer.RetailerRegistry.class);
    private final ProductPriceService productPriceService = mock(ProductPriceService.class);
    private final RetailerLinkRepository retailerLinkRepository = mock(RetailerLinkRepository.class);
    private final SearchClassifier searchClassifier = new SearchClassifier();
    private final CategoryDiscoveryService categoryDiscoveryService = mock(CategoryDiscoveryService.class);

    private SmartSearchService service() {
        return service(10);
    }

    private SmartSearchService service(int freshnessMinutes) {
        return new SmartSearchService(
                productService, productRepository, productPriceRepository, discoveryService,
                retailerRegistry, productPriceService, retailerLinkRepository,
                new ProductIdentityMatcher(), searchClassifier, categoryDiscoveryService,
                freshnessMinutes);
    }

    private static DiscoveredProduct candidate(String url, String title, String sku) {
        return new DiscoveredProduct(RetailerKey.VIJAY_SALES, url, title, sku,
                "", "", "In stock", "48000", "INR");
    }

    /* TEST 1 â€” empty query â†’ controlled response, no DB/discovery calls. */
    @Test
    void emptyQueryReturnsControlledResponse() {
        SmartSearchResponse r = service().search("   ");
        assertEquals("EMPTY_QUERY", r.decision());
        assertTrue(r.products().isEmpty());
        verify(productService, never()).searchProducts(anyString());
    }

    /* TEST 1 â€” fresh DB hit is served from cache; NO discovery is triggered. */
    @Test
    void freshHitIsServedFromCacheWithoutDiscovery() {
        Product p = new Product("LG 1.5 Ton 5 Star Split AC", "LG", "LS-Q18YNZA", "AC");
        ReflectionTestUtils.setField(p, "id", 1L);
        when(productService.searchProducts(anyString())).thenReturn(List.of(p));
        when(productPriceRepository.findByProductIdOrderByPriceAsc(any()))
                .thenReturn(List.of(new ProductPrice(p, "Vijay Sales",
                        new BigDecimal("48000"), Q20_URL, LocalDateTime.now(), "256812")));
        when(retailerLinkRepository.findByProductIdAndWebsite(anyLong(), anyString()))
                .thenReturn(Optional.of(new RetailerLink(p, "Vijay Sales", Q20_URL, "256812")));

        SmartSearchResponse r = service().search("LG AS-Q20ENZE");

        assertEquals("CACHE_HIT_FRESH", r.decision());
        assertEquals(1, r.products().size());
        verify(discoveryService, never()).discoverCandidates(anyString());
    }

    /* TEST 2 â€” stale DB hit is detected (older than the window); discovery not
       triggered; the product is still returned so the UI refreshes it live. */
    @Test
    void staleHitIsDetectedAndLeftToRefreshFlow() {
        Product p = new Product("LG 1.5 Ton 5 Star Split AC", "LG", "LS-Q18YNZA", "AC");
        ReflectionTestUtils.setField(p, "id", 1L);
        when(productService.searchProducts(anyString())).thenReturn(List.of(p));
        when(productPriceRepository.findByProductIdOrderByPriceAsc(any()))
                .thenReturn(List.of(new ProductPrice(p, "Vijay Sales",
                        new BigDecimal("48000"), Q20_URL,
                        LocalDateTime.now().minusHours(2), "256812")));
        when(retailerLinkRepository.findByProductIdAndWebsite(anyLong(), anyString()))
                .thenReturn(Optional.of(new RetailerLink(p, "Vijay Sales", Q20_URL, "256812")));

        SmartSearchResponse r = service().search("LG AS-Q20ENZE");

        assertEquals("CACHE_HIT_STALE", r.decision());
        assertEquals(1, r.products().size());
        verify(discoveryService, never()).discoverCandidates(anyString());
    }

    /* TEST 3 â€” DB miss: live discovery â†’ strict identity match â†’ product,
       retailer link and verified price are persisted. */
    @Test
    void dbMissDiscoversConfirmsAndPersistsVerifiedOffer() {
        Product p = new Product(Q20_TITLE, "LG", "AS-Q20ENZE", "");
        when(productService.searchProducts(anyString())).thenReturn(List.of());
        when(discoveryService.discoverCandidates(anyString()))
                .thenReturn(List.of(candidate(Q20_URL, Q20_TITLE, "256812")));
        when(productRepository.findByNameAndBrandAndModelAndCategory(anyString(), anyString(),
                anyString(), anyString())).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 6L);
            return saved;
        });
        when(productPriceRepository.findByProductIdOrderByPriceAsc(any())).thenReturn(List.of());
        when(retailerLinkRepository.findByProductIdAndWebsite(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        RetailerPriceProvider priceProvider = mock(RetailerPriceProvider.class);
        when(retailerRegistry.findByKey(RetailerKey.VIJAY_SALES))
                .thenReturn(Optional.of(priceProvider));
        when(priceProvider.fetchPrice(Q20_URL)).thenReturn(List.of(new RetailerPrice(
                RetailerKey.VIJAY_SALES, new BigDecimal("48000"), Q20_URL, Q20_TITLE,
                LocalDateTime.now(), "256812")));

        SmartSearchResponse r = service().search("LG AS-Q20ENZE");

        assertEquals("DB_MISS_DISCOVERED", r.decision());
        assertEquals(1, r.products().size());
        verify(productRepository).save(any(Product.class));
        verify(retailerLinkRepository).save(any(RetailerLink.class));
        verify(productPriceService).addPrice(anyLong(), eq("Vijay Sales"),
                eq(new BigDecimal("48000")), eq(Q20_URL), eq("256812"));
    }

    /* TEST 2 (wrong model) â€” AS-Q19CNZE1 is rejected for AS-Q20ENZE. */
    @Test
    void dbMissRejectsWrongModelStrictly() {
        when(productService.searchProducts(anyString())).thenReturn(List.of());
        when(discoveryService.discoverCandidates(anyString()))
                .thenReturn(List.of(candidate(Q19_URL, Q19_TITLE, "9000001")));

        SmartSearchResponse r = service().search("LG AS-Q20ENZE");

        assertEquals("DB_MISS_NO_MATCH", r.decision());
        assertTrue(r.products().isEmpty());
        verify(productRepository, never()).save(any(Product.class));
    }

    /* TEST â€” "{brand} {category}" with an existing DB product ("Samsung TV")
       must NOT become a category listing; it stays a specific-product search
       so the existing comparison flow (and Playwright test) keeps working. */
    @Test
    void brandPlusCategoryWithDbProductStaysSpecificSearch() {
        Product tv = new Product("Samsung 55 inch 4K Smart TV", "Samsung", "UA55", "Television");
        ReflectionTestUtils.setField(tv, "id", 2L);
        when(productService.searchProducts(anyString())).thenReturn(List.of(tv));
        when(productPriceRepository.findByProductIdOrderByPriceAsc(any()))
                .thenReturn(List.of(new ProductPrice(tv, "Vijay Sales",
                        new BigDecimal("18000"), Q20_URL, LocalDateTime.now(), "10001")));
        when(retailerLinkRepository.findByProductIdAndWebsite(anyLong(), anyString()))
                .thenReturn(Optional.of(new RetailerLink(tv, "Vijay Sales", Q20_URL, "10001")));

        SmartSearchResponse r = service().search("SAMSUNG TV");

        assertEquals("CACHE_HIT_FRESH", r.decision());
        assertEquals(1, r.products().size());
        assertEquals("Samsung 55 inch 4K Smart TV", r.products().get(0).name());
        verify(discoveryService, never()).discoverCandidates(anyString());
    }

    /* Pure category alias always yields a listing, never a DB product lookup. */
    @Test
    void pureCategoryAliasAlwaysReturnsListing() {
        CategoryListingResponse listing = new CategoryListingResponse(
                "mobiles", "mobile", "Mobiles", "desc", 0, List.of(), List.of());
        when(categoryDiscoveryService.discover(any(), anyString(), anyInt(), anyInt())).thenReturn(listing);

        SmartSearchResponse r = service().search("mobiles");

        assertEquals("CATEGORY_RESULT", r.decision());
        assertNotNull(r.categoryListing());
        assertEquals("mobile", r.categoryListing().category());
        // A DB product search (which previously surfaced the "warranty" row)
        // must NOT run for a pure category alias.
        verify(productService, never()).searchProducts(anyString());
    }

    /* TEST 2 (no candidates) â€” discovery returned nothing â†’ controlled empty. */
    @Test
    void dbMissWithNoCandidatesIsControlled() {
        when(productService.searchProducts(anyString())).thenReturn(List.of());
        when(discoveryService.discoverCandidates(anyString())).thenReturn(List.of());

        SmartSearchResponse r = service().search("Samsung S25");

        assertEquals("DB_MISS_NO_MATCH", r.decision());
        assertFalse(r.message().isBlank());
        assertTrue(r.products().isEmpty());
        verify(productRepository, never()).save(any(Product.class));
    }

    /* TEST 4 â€” a repeat search never duplicates: the persisted product is found
       by exact identity and reused, no second save. */
    @Test
    void repeatSearchReusesExistingProductWithoutDuplicating() {
        Product existing = new Product(Q20_TITLE, "LG", "AS-Q20ENZE", "");
        when(productService.searchProducts(anyString())).thenReturn(List.of());
        when(discoveryService.discoverCandidates(anyString()))
                .thenReturn(List.of(candidate(Q20_URL, Q20_TITLE, "256812")));
        when(productRepository.findByNameAndBrandAndModelAndCategory(Q20_TITLE,
                "", "256812", "")).thenReturn(Optional.of(existing));
        when(productPriceRepository.findByProductIdOrderByPriceAsc(any())).thenReturn(List.of());
        when(retailerLinkRepository.findByProductIdAndWebsite(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(retailerRegistry.findByKey(RetailerKey.VIJAY_SALES)).thenReturn(Optional.empty());

        SmartSearchResponse r = service().search("LG AS-Q20ENZE");

        assertEquals("DB_MISS_DISCOVERED", r.decision());
        assertEquals(1, r.products().size());
        verify(productRepository, never()).save(any(Product.class));
    }

    /* TEST 5/6 â€” a live-price-provider failure never fails discovery; the
       confirmed product and link are still persisted. */
    @Test
    void priceProviderFailureIsIsolatedProductStillPersisted() {
        when(productService.searchProducts(anyString())).thenReturn(List.of());
        when(discoveryService.discoverCandidates(anyString()))
                .thenReturn(List.of(candidate(Q20_URL, Q20_TITLE, "256812")));
        when(productRepository.findByNameAndBrandAndModelAndCategory(anyString(), anyString(),
                anyString(), anyString())).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 6L);
            return saved;
        });
        when(productPriceRepository.findByProductIdOrderByPriceAsc(any())).thenReturn(List.of());
        when(retailerLinkRepository.findByProductIdAndWebsite(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        RetailerPriceProvider priceProvider = mock(RetailerPriceProvider.class);
        when(retailerRegistry.findByKey(RetailerKey.VIJAY_SALES))
                .thenReturn(Optional.of(priceProvider));
        when(priceProvider.fetchPrice(anyString()))
                .thenThrow(new RuntimeException("Vijay Sales temporarily unavailable"));

        SmartSearchResponse r = service().search("LG AS-Q20ENZE");

        assertEquals("DB_MISS_DISCOVERED", r.decision());
        assertEquals(1, r.products().size());
        verify(productRepository).save(any(Product.class));
        verify(retailerLinkRepository).save(any(RetailerLink.class));
        verify(productPriceService, never()).addPrice(anyLong(), anyString(), any(), anyString());
    }
    /** Test that stale prices are preserved with fresh=false. */
    @Test
    void staleCacheHitReturnsRealPricesWithFreshFalse() {
        Product product = new Product("LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)", "LG", "AS-Q20ENZE", "");
        ReflectionTestUtils.setField(product, "id", 1L);
        when(productService.searchProducts(anyString())).thenReturn(List.of(product));
        ProductPrice stalePrice = new ProductPrice();
        ReflectionTestUtils.setField(stalePrice, "id", 1L);
        ReflectionTestUtils.setField(stalePrice, "product", product);
        ReflectionTestUtils.setField(stalePrice, "website", "Vijay Sales");
        ReflectionTestUtils.setField(stalePrice, "price", new BigDecimal("45490"));
        ReflectionTestUtils.setField(stalePrice, "productUrl", Q20_URL);
        ReflectionTestUtils.setField(stalePrice, "sku", "256812");
        ReflectionTestUtils.setField(stalePrice, "lastUpdated", LocalDateTime.now().minusMinutes(120));
        when(productPriceRepository.findByProductIdOrderByPriceAsc(any())).thenReturn(List.of(stalePrice));
        
        RetailerLink link = new RetailerLink();
        ReflectionTestUtils.setField(link, "sku", "256812");
        ReflectionTestUtils.setField(link, "productUrl", Q20_URL);
        when(retailerLinkRepository.findByProductIdAndWebsite(eq(1L), eq("Vijay Sales")))
                .thenReturn(Optional.of(link));
        when(retailerRegistry.findByKey(RetailerKey.VIJAY_SALES)).thenReturn(Optional.empty());
        
        SmartSearchService service = service(10);
        SmartSearchResponse r = service.search("LG AS-Q20ENZE");
        
        assertEquals("CACHE_HIT_STALE", r.decision());
        assertEquals(1, r.products().size());
        var prices = r.products().get(0).prices();
        assertEquals(1, prices.size());
        assertEquals("Vijay Sales", prices.get(0).retailer());
        assertEquals(new BigDecimal("45490"), prices.get(0).price());
        assertEquals(Q20_URL, prices.get(0).productUrl());
        assertFalse(prices.get(0).fresh(), "Stale prices must be marked fresh=false");
    }

    @Test
    void freshCacheHitReturnsRealPricesWithFreshTrue() {
        Product product = new Product("LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)", "LG", "AS-Q20ENZE", "");
        ReflectionTestUtils.setField(product, "id", 1L);
        when(productService.searchProducts(anyString())).thenReturn(List.of(product));
        ProductPrice freshPrice = new ProductPrice();
        ReflectionTestUtils.setField(freshPrice, "id", 1L);
        ReflectionTestUtils.setField(freshPrice, "product", product);
        ReflectionTestUtils.setField(freshPrice, "website", "Vijay Sales");
        ReflectionTestUtils.setField(freshPrice, "price", new BigDecimal("45490"));
        ReflectionTestUtils.setField(freshPrice, "productUrl", Q20_URL);
        ReflectionTestUtils.setField(freshPrice, "sku", "256812");
        ReflectionTestUtils.setField(freshPrice, "lastUpdated", LocalDateTime.now().minusMinutes(5));
        when(productPriceRepository.findByProductIdOrderByPriceAsc(any())).thenReturn(List.of(freshPrice));
        
        RetailerLink link = new RetailerLink();
        ReflectionTestUtils.setField(link, "sku", "256812");
        ReflectionTestUtils.setField(link, "productUrl", Q20_URL);
        when(retailerLinkRepository.findByProductIdAndWebsite(eq(1L), eq("Vijay Sales")))
                .thenReturn(Optional.of(link));
        when(retailerRegistry.findByKey(RetailerKey.VIJAY_SALES)).thenReturn(Optional.empty());
        
        SmartSearchService service = service(10);
        SmartSearchResponse r = service.search("LG AS-Q20ENZE");
        
        assertEquals("CACHE_HIT_FRESH", r.decision());
        assertEquals(1, r.products().size());
        var prices = r.products().get(0).prices();
        assertEquals(1, prices.size());
        assertTrue(prices.get(0).fresh(), "Fresh prices must be marked fresh=true");
        assertEquals(new BigDecimal("45490"), prices.get(0).price());
    }

    @Test
    void noStoredPricesFallsThroughToDiscovery() {
        Product product = new Product("LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)", "LG", "AS-Q20ENZE", "");
        when(productService.searchProducts(anyString())).thenReturn(List.of(product));
        when(productPriceRepository.findByProductIdOrderByPriceAsc(any())).thenReturn(List.of());
        
        SmartSearchService service = service(10);
        SmartSearchResponse r = service.search("LG AS-Q20ENZE");
        
        assertEquals("DB_MISS_NO_MATCH", r.decision());
        assertTrue(r.products().isEmpty());
    }
}
