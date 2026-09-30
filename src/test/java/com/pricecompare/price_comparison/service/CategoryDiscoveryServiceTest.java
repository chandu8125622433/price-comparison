package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.retailer.discovery.DiscoveredProduct;
import com.pricecompare.price_comparison.retailer.discovery.ProductDiscoveryProvider;
import com.pricecompare.price_comparison.retailer.discovery.ProductDiscoveryRegistry;
import com.pricecompare.price_comparison.repository.RetailerLinkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryDiscoveryServiceTest {

    private static final String BASE_URL = "https://www.vijaysales.com/p/";

    private static DiscoveredProduct product(int index) {
        String sku = String.valueOf(200000 + index);
        String title = "Test TV " + sku;
        String url = BASE_URL + sku + "/test-tv-" + sku;
        return new DiscoveredProduct(
                RetailerKey.VIJAY_SALES,
                url,
                title,
                sku,
                "https://cdn.vijaysales.com/img/" + sku + ".jpg",
                "29990",
                "In stock");
    }

    private static DiscoveredProduct makeProduct(String sourceCategory) {
        return new DiscoveredProduct(
                RetailerKey.VIJAY_SALES,
                BASE_URL + "123456/test-product",
                "Test Product",
                "123456",
                "https://cdn.vijaysales.com/img/123456.jpg",
                "29990",
                "In stock",
                "", "", sourceCategory);
    }

    private static List<DiscoveredProduct> products(int count) {
        return IntStream.range(0, count)
                .mapToObj(CategoryDiscoveryServiceTest::product)
                .collect(Collectors.toList());
    }

    private static ProductDiscoveryRegistry registry(ProductDiscoveryProvider... providers) {
        return new ProductDiscoveryRegistry(List.of(providers));
    }

    @Test
    void categoryDiscoveryCollectsMoreThanTenCandidates() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(products(20));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider),
                new SearchClassifier(),
                new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var response = service.discover(SearchClassifier.Category.TV, "TV", 1, 100);

        assertEquals(20, response.totalProducts(),
                "Category discovery should collect up to 100 candidates, not 10");
    }

    @Test
    void categoryDiscoveryRespectsPageSize() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(products(50));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider),
                new SearchClassifier(),
                new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var response = service.discover(SearchClassifier.Category.TV, "TV", 1, 100);

        assertTrue(response.totalProducts() >= 20,
                "Should return more than 10 candidates (previously capped at 10)");
        assertEquals(50, response.products().size(),
                "All 50 unique products should be returned when pageSize allows");
    }

    @Test
    void deduplicationStillWorks() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        List<DiscoveredProduct> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            candidates.add(product(0));
        }
        Mockito.when(provider.discover(Mockito.anyString())).thenReturn(candidates);

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider),
                new SearchClassifier(),
                new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var response = service.discover(SearchClassifier.Category.TV, "TV", 1, 100);

        assertEquals(1, response.totalProducts(),
                "Duplicate candidates should be deduplicated by URL");
    }

    @Test
    void accessoriesAreFilteredOut() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        List<DiscoveredProduct> candidates = new ArrayList<>();
        candidates.add(new DiscoveredProduct(
                RetailerKey.VIJAY_SALES,
                BASE_URL + "999999/tv-wall-mount",
                "Samsung TV Wall Mount",
                "999999",
                "https://cdn.vijaysales.com/img/999999.jpg",
                "1999",
                "In stock"));
        candidates.add(product(1));
        Mockito.when(provider.discover(Mockito.anyString())).thenReturn(candidates);

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider),
                new SearchClassifier(),
                new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var response = service.discover(SearchClassifier.Category.TV, "TV", 1, 100);

        assertEquals(1, response.totalProducts(),
                "Accessory products should be rejected by CategoryRelevanceFilter");
        assertFalse(response.products().stream()
                .anyMatch(p -> p.name().toLowerCase(Locale.ROOT).contains("wall mount")),
                "No wall mount products should appear in TV category results");
    }

    @Test
    void emptyQueryReturnsEmptyResults() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString())).thenReturn(List.of());

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider),
                new SearchClassifier(),
                new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var response = service.discover(SearchClassifier.Category.TV, "TV", 1, 100);

        assertEquals(0, response.totalProducts());
    }

    @Test
    void fullTraversalReturnsAllAvailableProducts() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(products(200));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider),
                new SearchClassifier(),
                new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var response = service.discover(SearchClassifier.Category.TV, "TV", 1, 100);

        assertEquals(200, response.totalProducts(),
                "Full catalogue should be traversed, not capped at 10");
    }

    @Test
    void demoProductsAreExcludedFromLiveResults() {
        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
        List<DiscoveredProduct> candidates = new ArrayList<>();
        candidates.add(new DiscoveredProduct(
                RetailerKey.VIJAY_SALES,
                "https://www.vijaysales.com/p/123456/samsung-tv",
                "Samsung 55 inch 4K Smart TV",
                "123456",
                "https://cdn.vijaysales.com/img/123456.jpg",
                "59990",
                "In stock"));
        candidates.add(new DiscoveredProduct(
                RetailerKey.RELIANCE_DIGITAL,
                "https://example.com/demo/samsung-tv",
                "Samsung 55 inch 4K Smart TV",
                "123456",
                "https://example.com/demo/123456.jpg",
                "74999",
                "In stock"));
        Mockito.when(provider.discover(Mockito.anyString())).thenReturn(candidates);

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider),
                new SearchClassifier(),
                new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var response = service.discover(SearchClassifier.Category.TV, "TV", 1, 100);

        assertFalse(response.products().stream()
                .anyMatch(p -> p.productUrl() != null
                        && p.productUrl().contains("example.com")),
                "Demo/seed products with example.com URLs must not appear as live results");
    }

    @Test
    void accessoriesAreRejectedInAllCategories() {
        String[][] cases = {
                {SearchClassifier.Category.TV.name(), "Samsung TV Wall Mount"},
                {SearchClassifier.Category.MOBILE.name(), "Samsung Galaxy Phone Case"},
                {SearchClassifier.Category.LAPTOP.name(), "MacBook Laptop Bag"},
                {SearchClassifier.Category.HEADPHONES.name(), "Sony WH-1000XM5 Charger"},
                {SearchClassifier.Category.TABLET.name(), "iPad Screen Protector"},
                {SearchClassifier.Category.CAMERA.name(), "Camera Tripod"},
                {SearchClassifier.Category.SMARTWATCH.name(), "Apple Watch Band"},
                {SearchClassifier.Category.AC.name(), "AC Installation Service"},
                {SearchClassifier.Category.REFRIGERATOR.name(), "Refrigerator Warranty Plan"},
                {SearchClassifier.Category.WASHING_MACHINE.name(), "Washing Machine Stand"},
        };
        for (String[] c : cases) {
            SearchClassifier.Category category = SearchClassifier.Category.valueOf(c[0]);
            ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
            Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
            Mockito.when(provider.discover(Mockito.anyString()))
                    .thenReturn(List.of(new DiscoveredProduct(
                            RetailerKey.VIJAY_SALES,
                            BASE_URL + "999999/" + c[1].toLowerCase().replace(' ', '-'),
                            c[1], "999999",
                            "https://cdn.vijaysales.com/img/999999.jpg",
                            "999", "In stock")));

            CategoryDiscoveryService service = new CategoryDiscoveryService(
                    registry(provider),
                    new SearchClassifier(),
                    new CategoryRelevanceFilter(),
                    Mockito.mock(RetailerLinkRepository.class));

            var response = service.discover(category, c[0], 1, 100);

            assertTrue(response.products().isEmpty(),
                    c[1] + " should be rejected for " + c[0] + " category");
        }
    }

    @Test
    void warrantyProductsAreRejectedAcrossCategories() {
        String[][] cases = {
                {SearchClassifier.Category.MOBILE.name(), "1 Yr VS+ EW for Mobiles"},
                {SearchClassifier.Category.TV.name(), "1 Yr VS+ EW for Televisions"},
                {SearchClassifier.Category.HEADPHONES.name(), "Sony Warranty Service"},
                {SearchClassifier.Category.LAPTOP.name(), "MacBook Extended Warranty"},
        };
        for (String[] c : cases) {
            SearchClassifier.Category category = SearchClassifier.Category.valueOf(c[0]);
            ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
            Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
            Mockito.when(provider.discover(Mockito.anyString()))
                    .thenReturn(List.of(new DiscoveredProduct(
                            RetailerKey.VIJAY_SALES,
                            BASE_URL + "999999/warranty",
                            c[1], "999999",
                            "https://cdn.vijaysales.com/img/999999.jpg",
                            "1999", "In stock")));

            CategoryDiscoveryService service = new CategoryDiscoveryService(
                    registry(provider),
                    new SearchClassifier(),
                    new CategoryRelevanceFilter(),
                    Mockito.mock(RetailerLinkRepository.class));

            var response = service.discover(category, c[0], 1, 100);

            assertTrue(response.products().isEmpty(),
                    "Warranty product '" + c[1] + "' should be rejected for " + c[0]);
        }
    }

    @Test
    void genuineProductsAreAcceptedInAllCategories() {
        String[][] cases = {
                {SearchClassifier.Category.MOBILE.name(), "Samsung Galaxy S25", "smartphone", "Mobile"},
                {SearchClassifier.Category.TV.name(), "LG 80 cm HD AI Smart WebOS23 LED TV", "tv", "Television"},
                {SearchClassifier.Category.LAPTOP.name(), "Apple MacBook Air M3", "laptop", "Laptop"},
                {SearchClassifier.Category.AC.name(), "LG 1.5 Ton 5 Star Split AC", "air conditioner", "AC"},
                {SearchClassifier.Category.REFRIGERATOR.name(), "Samsung 253L Double Door Refrigerator", "refrigerator", "Refrigerator"},
                {SearchClassifier.Category.WASHING_MACHINE.name(), "LG 8 Kg Front Load Washing Machine", "washing machine", "Washing Machine"},
                {SearchClassifier.Category.HEADPHONES.name(), "Sony WH-1000XM5 Wireless Headphones", "headphones", "Headphones"},
                {SearchClassifier.Category.TABLET.name(), "Samsung Galaxy Tab S9", "tablet", "Tablet"},
                {SearchClassifier.Category.CAMERA.name(), "Sony Alpha 7 IV Camera", "camera", "Camera"},
                {SearchClassifier.Category.SMARTWATCH.name(), "Apple Watch SE GPS 40mm", "smartwatch", "Smartwatch"},
        };
        for (String[] c : cases) {
            SearchClassifier.Category category = SearchClassifier.Category.valueOf(c[0]);
            ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
            Mockito.when(provider.retailer()).thenReturn(RetailerKey.VIJAY_SALES);
            Mockito.when(provider.discover(Mockito.anyString()))
                    .thenReturn(List.of(makeProduct(c[3])));

            CategoryDiscoveryService service = new CategoryDiscoveryService(
                    registry(provider),
                    new SearchClassifier(),
                    new CategoryRelevanceFilter(),
                    Mockito.mock(RetailerLinkRepository.class));

            var response = service.discover(category, c[2], 1, 100);

            assertEquals(1, response.totalProducts(),
                    "Genuine product '" + c[1] + "' should be accepted for " + c[0]);
        }
    }

    /**
     * Regression: two listings of the SAME product from the SAME retailer land in
     * one card, because grouping is title-based. Picking the cheaper of the two
     * re-read the already display-formatted price (rupee symbol + thousands
     * separators) with new BigDecimal(), which threw NumberFormatException on the
     * currency character and failed the entire category search with HTTP 400.
     */
    @Test
    void cardPicksCheaperOfferWhenSameRetailerListsProductTwice() {
        String title = "Test TV 9000001";
        DiscoveredProduct dearer = pricedProduct(
                "https://www.reliancedigital.in/product/test-tv-9000001-a", title, "9000001-A", "39990");
        DiscoveredProduct cheaper = pricedProduct(
                "https://www.reliancedigital.in/product/test-tv-9000001-b", title, "9000001-B", "29990");

        ProductDiscoveryProvider provider = Mockito.mock(ProductDiscoveryProvider.class);
        Mockito.when(provider.retailer()).thenReturn(RetailerKey.RELIANCE_DIGITAL);
        Mockito.when(provider.discover(Mockito.anyString()))
                .thenReturn(List.of(dearer, cheaper));

        CategoryDiscoveryService service = new CategoryDiscoveryService(
                registry(provider),
                new SearchClassifier(),
                new CategoryRelevanceFilter(),
                Mockito.mock(RetailerLinkRepository.class));

        var response = service.discover(SearchClassifier.Category.TV, "TV", 1, 100);

        assertEquals(1, response.totalProducts(),
                "Both listings describe one product, so they form a single card");
        var card = response.products().get(0);
        assertEquals(1, card.retailers().size(), "One retailer, so one offer");
        assertEquals(1, card.offers().size(), "Each retailer contributes one offer");
        assertEquals("29990", digitsOnly(card.offers().get(0).price()),
                "The cheaper of the two same-retailer listings must win");
        assertEquals("29990", digitsOnly(card.lowestPrice()));
    }

    /** A candidate that carries a real discovered selling price, as RD reports one. */
    private static DiscoveredProduct pricedProduct(String url, String title, String sku, String price) {
        return new DiscoveredProduct(
                RetailerKey.RELIANCE_DIGITAL,
                url,
                title,
                sku,
                "https://cdn.reliancedigital.in/img/" + sku + ".jpg",
                "",
                "In stock",
                price,
                "INR");
    }

    /** Digits only, so the assertion never depends on the display format. */
    private static String digitsOnly(String price) {
        return price == null ? null : price.replaceAll("[^0-9]", "");
    }
}
