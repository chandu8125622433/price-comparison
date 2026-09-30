package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.retailer.discovery.DiscoveredProduct;
import com.pricecompare.price_comparison.retailer.discovery.ProductDiscoveryProvider;
import com.pricecompare.price_comparison.retailer.discovery.ProductDiscoveryRegistry;
import com.pricecompare.price_comparison.repository.RetailerLinkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for category-specific specification filters in CategoryDiscoveryService.
 *
 * <p>Specifications are extracted from product titles by {@link ProductSpecExtractor}
 * and used to filter the discovered product dataset.</p>
 */
class CategorySpecFilterTest {

    private static final String BASE_URL = "https://www.vijaysales.com/p/";

    private static DiscoveredProduct product(String title, String sku, String price) {
        String url = BASE_URL + sku + "/" + title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", "").replaceAll("\\s+", "-");
        return new DiscoveredProduct(
                RetailerKey.VIJAY_SALES,
                url,
                title,
                sku,
                "https://cdn.vijaysales.com/img/" + sku + ".jpg",
                price, "In stock", price, "INR");
    }

    private static ProductDiscoveryRegistry registry(ProductDiscoveryProvider... providers) {
        return new ProductDiscoveryRegistry(List.of(providers));
    }

    /* --- Specification extraction is reflected in product cards --- */

    @Test
    void productCardsCarryExtractedSpecs() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(List.of(product("Samsung 55 inch 4K Smart TV", "123456", "59990")));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider), new SearchClassifier(), new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var response = service.discover(SearchClassifier.Category.TV, "TV", 1, 100);

        assertFalse(response.products().isEmpty());
        assertEquals(Optional.of(55), response.products().get(0).specs().screenSize());
        assertEquals(Optional.of("4K"), response.products().get(0).specs().resolution());
    }

    /* --- RAM filter --- */

    @Test
    void ramFilterMatchesOnlyProductsWithSpecifiedRam() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(List.of(
                        product("Samsung Galaxy M36 8GB/256GB", "1", "19999"),
                        product("Samsung Galaxy M36 6GB/128GB", "2", "17999"),
                        product("Samsung Galaxy M36 12GB/256GB", "3", "21999")));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider), new SearchClassifier(), new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var filtered = service.discover(SearchClassifier.Category.MOBILE, "Samsung Galaxy M36",
                1, 100, List.of(), null, null, "price-asc", Map.<String, List<String>>of("ram", List.of("8")));

        assertEquals(1, filtered.totalProducts());
        assertEquals(8, filtered.products().get(0).specs().ram().get());
    }

    /* --- Processor filter --- */

    @Test
    void processorFilterMatchesOnlyProductsWithSpecifiedProcessor() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(List.of(
                        product("Xiaomi Redmi Note 13 Pro Snapdragon 7s Gen 3", "1", "19999"),
                        product("Xiaomi Redmi Note 13 Pro MediaTek Dimensity 7200", "2", "18999")));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider), new SearchClassifier(), new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var filtered = service.discover(SearchClassifier.Category.MOBILE, "Redmi Note 13",
                1, 100, List.of(), null, null, "price-asc", Map.<String, List<String>>of("processor", List.of("Snapdragon 7s")));

        assertEquals(1, filtered.totalProducts());
        assertTrue(filtered.products().get(0).specs().processor().isPresent());
        assertTrue(filtered.products().get(0).specs().processor().get().contains("Snapdragon"));
    }

    /* --- Screen size filter --- */

    @Test
    void screenSizeFilterMatchesOnlyProductsWithSpecifiedScreenSize() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(List.of(
                        product("Samsung 55 inch 4K Smart TV", "1", "59990"),
                        product("Samsung 65 inch 4K Smart TV", "2", "79990"),
                        product("LG 55 inch OLED Smart TV", "3", "89990")));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider), new SearchClassifier(), new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var filtered = service.discover(SearchClassifier.Category.TV, "TV",
                1, 100, List.of(), null, null, "price-asc", Map.<String, List<String>>of("screenSize", List.of("65")));

        assertEquals(1, filtered.totalProducts());
        assertEquals(65, filtered.products().get(0).specs().screenSize().get());
    }

    /* --- Combined filters --- */

    @Test
    void combinedRamAndPriceFiltersWorkTogether() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(List.of(
                        product("Samsung Galaxy M36 8GB/256GB", "1", "19999"),
                        product("Samsung Galaxy M36 8GB/128GB", "2", "15999"),
                        product("Samsung Galaxy M36 6GB/128GB", "3", "13999")));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider), new SearchClassifier(), new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        // RAM=8 AND price min=16000
        BigDecimal minPrice = new BigDecimal("16000");
        var filtered = service.discover(SearchClassifier.Category.MOBILE, "Samsung Galaxy M36",
                1, 100, List.of(), minPrice, null, "price-asc", Map.<String, List<String>>of("ram", List.of("8")));

        assertEquals(1, filtered.totalProducts());
        assertEquals("Samsung Galaxy M36 8GB/256GB", filtered.products().get(0).name());
    }

    @Test
    void combinedBrandAndSpecFiltersWorkTogether() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(List.of(
                        product("Samsung 55 inch 4K Smart TV", "1", "59990"),
                        product("LG 55 inch 4K Smart TV", "2", "69990")));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider), new SearchClassifier(), new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var filtered = service.discover(SearchClassifier.Category.TV, "TV",
                1, 100, List.of("Samsung"), null, null, "price-asc", Map.<String, List<String>>of("screenSize", List.of("55")));

        assertEquals(1, filtered.totalProducts());
        assertEquals("Samsung", filtered.products().get(0).brand());
    }

    /* --- Spec filter groups are built from actual results --- */

    @Test
    void specFilterGroupsBuiltFromDiscoveredProducts() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(List.of(
                        product("Samsung 55 inch 4K Smart TV", "1", "59990"),
                        product("Samsung 65 inch 4K Smart TV", "2", "79990"),
                        product("LG 55 inch Full HD OLED Smart TV", "3", "89990")));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider), new SearchClassifier(), new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var response = service.discover(SearchClassifier.Category.TV, "TV", 1, 100);

        boolean hasScreenSizeFilter = response.filters().stream()
                .anyMatch(f -> f.key().equals("screenSize"));
        assertTrue(hasScreenSizeFilter, "Should have screen size filter group");

        boolean hasResolutionFilter = response.filters().stream()
                .anyMatch(f -> f.key().equals("resolution"));
        assertTrue(hasResolutionFilter, "Should have resolution filter group");

        var screenSizeFilter = response.filters().stream()
                .filter(f -> f.key().equals("screenSize"))
                .findFirst();
        assertTrue(screenSizeFilter.isPresent());
        assertEquals(2, screenSizeFilter.get().options().size(),
                "Two distinct screen sizes (55 and 65)");
    }

    @Test
    void noSpecFilterWhenOnlyOneValueExists() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(List.of(
                        product("Samsung 55 inch 4K Smart TV", "1", "59990")));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider), new SearchClassifier(), new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var response = service.discover(SearchClassifier.Category.TV, "TV", 1, 100);

        assertFalse(response.filters().stream()
                        .filter(f -> !f.key().equals("brand"))
                        .anyMatch(f -> f.options().size() < 2),
                "Spec filter groups should require at least 2 distinct values");
    }

    /* --- Missing specification fields --- */

    @Test
    void specFilterWithNoMatchingProductsReturnsEmpty() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(List.of(
                        product("Samsung 55 inch 4K Smart TV", "1", "59990"),
                        product("Samsung 65 inch 4K Smart TV", "2", "79990")));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider), new SearchClassifier(), new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        // Filter by RAM=8, but no products have RAM in their titles
        var filtered = service.discover(SearchClassifier.Category.TV, "TV",
                1, 100, List.of(), null, null, "price-asc", Map.<String, List<String>>of("ram", List.of("8")));

        assertEquals(0, filtered.totalProducts(),
                "No TV products should match RAM filter since TVs have no RAM in titles");
    }

    @Test
    void productsWithoutSpecsPassThroughUnfiltered() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(List.of(
                        product("Samsung Galaxy S25", "1", "79999"),
                        product("Apple iPhone 16", "2", "82999")));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider), new SearchClassifier(), new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        // Filter by RAM=8 — these products have no specs, so none match
        var filtered = service.discover(SearchClassifier.Category.MOBILE, "Samsung Galaxy",
                1, 100, List.of(), null, null, "price-asc", Map.<String, List<String>>of("ram", List.of("8")));

        assertEquals(0, filtered.totalProducts());
    }

    /* --- No fabricated specification values --- */

    @Test
    void specValuesMatchExactlyWhatIsInTitle() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(List.of(product("Samsung 55 inch 4K Smart TV", "123456", "59990")));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider), new SearchClassifier(), new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var response = service.discover(SearchClassifier.Category.TV, "TV", 1, 100);

        var product = response.products().get(0);
        assertEquals(Optional.of(55), product.specs().screenSize());
        assertEquals(Optional.of("4K"), product.specs().resolution());
        assertFalse(product.specs().ram().isPresent(), "TVs should not have RAM fabricated");
        assertFalse(product.specs().tonnage().isPresent(), "TVs should not have tonnage fabricated");
    }

    /* --- Empty spec filters do not filter --- */

    @Test
    void emptySpecFiltersDoNotAffectResults() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(List.of(
                        product("Samsung 55 inch 4K Smart TV", "1", "59990"),
                        product("Samsung 65 inch 4K Smart TV", "2", "79990")));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider), new SearchClassifier(), new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var filtered = service.discover(SearchClassifier.Category.TV, "TV",
                1, 100, List.of(), null, null, "price-asc", Map.<String, List<String>>of());

        assertEquals(2, filtered.totalProducts());
    }
}
