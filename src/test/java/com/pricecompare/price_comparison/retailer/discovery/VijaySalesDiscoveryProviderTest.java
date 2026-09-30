package com.pricecompare.price_comparison.retailer.discovery;

import com.pricecompare.price_comparison.retailer.RetailerException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
/**
 * Deterministic unit tests for the Vijay Sales discovery provider:
 * response parsing, URL filtering, de-duplication, limits and query
 * normalization. No network access is performed.
 */
class VijaySalesDiscoveryProviderTest {

    private final VijaySalesDiscoveryProvider provider =
            new VijaySalesDiscoveryProvider(VijaySalesDiscoveryProvider.DEFAULT_PAGE_SIZE, VijaySalesDiscoveryProvider.DEFAULT_SAFETY_LIMIT);

    private static String payload(String itemsJson) {
        return "{\"data\":{\"products\":{\"total_count\":2,\"items\":[" + itemsJson + "]}}}";
    }

    private static String item(String name, String sku, String url, String stock) {
        return "{\"name\":\"" + name + "\",\"sku\":\"" + sku + "\",\"product_url\":\""
                + url + "\",\"stock_status\":\"" + stock + "\",\"mrp\":\"20990\","
                + "\"image\":{\"url\":\"https://cdn.vijaysales.com/img/" + sku + ".jpg\"}}";
    }

    @Test
    void parsesCandidatesWithMappedFields() {
        String json = payload(item("LG 32 inch HD Smart TV", "238476",
                "/p/238476/lg-32-inch-hd-smart-tv", "IN_STOCK"));

        var result = provider.parseResponse(json, 5);

        assertEquals(1, result.size());
        DiscoveredProduct c = result.get(0);
        assertEquals(RetailerKey.VIJAY_SALES, c.retailer());
        assertEquals("https://www.vijaysales.com/p/238476/lg-32-inch-hd-smart-tv",
                c.productUrl());
        assertEquals("LG 32 inch HD Smart TV", c.productTitle());
        assertEquals("238476", c.sku());
        assertEquals("20990", c.mrp());
        assertEquals("In stock", c.availability());
        assertEquals("https://cdn.vijaysales.com/img/238476.jpg", c.imageUrl());
    }

    @Test
    void duplicateProductUrlsAreRemoved() {
        String json = payload(
                item("TV A", "1", "/p/1/tv-a", "IN_STOCK") + ","
                        + item("TV A dup", "1", "https://www.vijaysales.com/p/1/tv-a/", "IN_STOCK"));

        assertEquals(1, provider.parseResponse(json, 5).size());
    }

    @Test
    void candidateLimitIsEnforced() {
        String json = payload(
                item("A", "1", "/p/1/a", "IN_STOCK") + ","
                        + item("B", "2", "/p/2/b", "IN_STOCK") + ","
                        + item("C", "3", "/p/3/c", "IN_STOCK"));

        assertEquals(2, provider.parseResponse(json, 2).size());
    }

    @Test
    void nonProductUrlsAreIgnored() {
        String json = payload(
                item("Category page", "c1", "https://www.vijaysales.com/c/televisions", "IN_STOCK") + ","
                        + item("Other host", "x1", "https://evil.example.com/p/1/x", "IN_STOCK") + ","
                        + item("Bare sku path", "b1", "https://www.vijaysales.com/p/238476", "IN_STOCK") + ","
                        + item("Non-http", "n1", "ftp://www.vijaysales.com/p/1/x", "IN_STOCK") + ","
                        + item("Valid TV", "238476", "/p/238476/lg-32-inch-hd-smart-tv", "IN_STOCK"));

        var result = provider.parseResponse(json, 5);
        assertEquals(1, result.size());
        assertEquals("Valid TV", result.get(0).productTitle());
    }

    @Test
    void itemsWithoutTitleOrUrlAreIgnored() {
        String json = payload(
                "{\"name\":\"\",\"sku\":\"1\",\"product_url\":\"/p/1/a\"},"
                        + item("Valid", "2", "/p/2/b", "IN_STOCK"));

        var result = provider.parseResponse(json, 5);
        assertEquals(1, result.size());
        assertEquals("Valid", result.get(0).productTitle());
    }

    @Test
    void emptyResultIsHandledGracefully() {
        assertTrue(provider.parseResponse(
                "{\"data\":{\"products\":{\"total_count\":0,\"items\":[]}}}", 5).isEmpty());
        assertTrue(provider.parseResponse("{\"data\":{}}", 5).isEmpty());
    }

    @Test
    void malformedResponseRaisesControlledFailure() {
        RetailerException ex = assertThrows(RetailerException.class,
                () -> provider.parseResponse("not-json", 5));
        assertTrue(ex.getMessage().contains("could not be parsed"));
    }

    @Test
    void blankQueryShortCircuitsWithoutNetwork() {
        assertTrue(provider.discover("   ").isEmpty());
        assertTrue(provider.discover(null).isEmpty());
    }

    @Test
    void isTrackableProductUrlValidation() {
        assertTrue(VijaySalesDiscoveryProvider.isTrackableProductUrl(
                "https://www.vijaysales.com/p/238476/lg-32-inch-hd-smart-tv"));
        assertTrue(VijaySalesDiscoveryProvider.isTrackableProductUrl(
                "http://www.vijaysales.com/p/P239250/239250/tcl-32s5k"));
        assertFalse(VijaySalesDiscoveryProvider.isTrackableProductUrl(null));
        assertFalse(VijaySalesDiscoveryProvider.isTrackableProductUrl("  "));
        assertFalse(VijaySalesDiscoveryProvider.isTrackableProductUrl(
                "https://www.vijaysales.com/c/televisions"));
        assertFalse(VijaySalesDiscoveryProvider.isTrackableProductUrl(
                "https://www.vijaysales.com/search?q=tv"));
        assertFalse(VijaySalesDiscoveryProvider.isTrackableProductUrl(
                "https://other.example.com/p/1/x"));
        assertFalse(VijaySalesDiscoveryProvider.isTrackableProductUrl(
                "https://www.vijaysales.com/p/238476"));
    }

    @Test
    void queryIsSanitizedForGraphqlLiteral() {
        assertEquals("Samsung 55 inch 4K Smart TV",
                VijaySalesDiscoveryProvider.normalizeQuery("  Samsung \"55\" inch\t4K\nSmart TV "));
        assertEquals("samsung tv", VijaySalesDiscoveryProvider.normalizeQuery("samsung\\tv"));
        assertEquals("", VijaySalesDiscoveryProvider.normalizeQuery("\"\\\""));

        String query = VijaySalesDiscoveryProvider.buildGraphqlQuery("samsung tv", 5, 1);
        assertTrue(query.startsWith("{products(search:\"samsung tv\",pageSize:5,currentPage:1)"));
        assertTrue(query.contains("product_url"));
        assertTrue(query.endsWith("}"));
    }

    @Test
    void availabilityMapping() {
        assertEquals("In stock", VijaySalesDiscoveryProvider.mapAvailability("IN_STOCK"));
        assertEquals("Out of stock", VijaySalesDiscoveryProvider.mapAvailability("OUT_OF_STOCK"));
        assertEquals("", VijaySalesDiscoveryProvider.mapAvailability("UNKNOWN"));
        assertEquals("", VijaySalesDiscoveryProvider.mapAvailability(""));
        assertEquals("", VijaySalesDiscoveryProvider.mapAvailability(null));
    }

    @Test
    void warrantyAndServiceItemsAreFiltered() {
        String json = payload(
                item("Samsung Galaxy M36 5G Smartphone", "P251081", "/p/P251081/samsung-galaxy-m36", "IN_STOCK") + ","
                        + item("Store Display Unit - Samsung Galaxy Z Fold7 1 Year Onsite Go Warranty", "P242173", "/p/P242173/store-display", "IN_STOCK") + ","
                        + item("Extended Warranty for Mobiles", "EW123", "/p/EW123/extended-warranty", "IN_STOCK") + ","
                        + item("Samsung Galaxy Z Flip7 5G Smartphone", "P242165", "/p/P242165/samsung-galaxy-z-flip7", "IN_STOCK"));

        var result = provider.parseResponse(json, 10);
        assertEquals(2, result.size());
        assertEquals("Samsung Galaxy M36 5G Smartphone", result.get(0).productTitle());
        assertEquals("Samsung Galaxy Z Flip7 5G Smartphone", result.get(1).productTitle());
    }

    /** Phase 8B: "1 Yr VS+ EW" warranty format is rejected (previously passed). */
    @Test
    void vsPlusEwWarrantyItemsAreFiltered() {
        String json = payload(
                item("LG 80 cm (32 Inch) HD AI Smart WebOS23 LED TV", "32LR605B6LC", "/p/32LR605B6LC/lg-80-cm-32-inch-hd-ai-smart-webos23-led-tv", "IN_STOCK") + ","
                        + item("1 Yr VS+ EW for Televisions - 5K", "EW5K", "/p/EW5K/1-yr-vs-ew-for-televisions-5k", "IN_STOCK") + ","
                        + item("1 Yr VS+ EW for Televisions - 10K", "EW10K", "/p/EW10K/1-yr-vs-ew-for-televisions-10k", "IN_STOCK") + ","
                        + item("TCL 80 cm (32 inch) Full HD Smart Google QLED TV", "32S5K", "/p/32S5K/tcl-80-cm-32-inch-full-hd-smart-google-qled-tv", "IN_STOCK"));

        var result = provider.parseResponse(json, 10);
        assertEquals(2, result.size());
        assertEquals("LG 80 cm (32 Inch) HD AI Smart WebOS23 LED TV", result.get(0).productTitle());
        assertEquals("TCL 80 cm (32 inch) Full HD Smart Google QLED TV", result.get(1).productTitle());
    }

    @Test
    void totalCountIsParsedFromResponse() {
        String json = "{\"data\":{\"products\":{\"total_count\":1079,\"items\":[]}}}";
        assertEquals(1079, VijaySalesDiscoveryProvider.parseTotalCount(json));
    }

    @Test
    void totalCountReturnsMinusOneOnMissing() {
        assertEquals(-1, VijaySalesDiscoveryProvider.parseTotalCount("{\"data\":{}}"));
        assertEquals(-1, VijaySalesDiscoveryProvider.parseTotalCount("not-json"));
    }

    private static RestClient.Builder testRestClient() {
        return RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory());
    }

    private static String buildPaginatedPayload(int itemCount, String pageTag, int totalCount) {
        if (itemCount == 0) {
            return "{\"data\":{\"products\":{\"total_count\":" + totalCount + ",\"items\":[]}}}";
        }
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < itemCount; i++) {
            if (i > 0) items.append(",");
            String sku = pageTag + "-sku-" + i;
            String name = "Samsung Galaxy M36 " + pageTag + " variant " + i;
            String url = "/p/" + sku + "/samsung-galaxy-m36-" + pageTag + "-variant-" + i;
            items.append("{\"name\":\"").append(name).append("\",")
                 .append("\"sku\":\"").append(sku).append("\",")
                 .append("\"product_url\":\"").append(url).append("\",")
                 .append("\"stock_status\":\"IN_STOCK\",")
                 .append("\"mrp\":\"18000\",")
                 .append("\"image\":{\"url\":\"https://cdn.vijaysales.com/img/").append(sku).append(".jpg\"},")
                 .append("\"price_range\":{\"maximum_price\":{\"final_price\":{\"value\":\"17500\",\"currency\":\"INR\"}}}}");
        }
        return "{\"data\":{\"products\":{\"total_count\":" + totalCount + ",\"items\":[" + items + "]}}}";
    }

    /** A: Multiple pages are requested when more results exist. */
    @Test
    void multiplePagesRequestedWhenMoreResultsExist() throws Exception {
        RestClient.Builder builder = testRestClient();
        MockRestServiceServer server = MockRestServiceServer.createServer(builder);
        server.expect(ExpectedCount.once(), (ClientHttpRequest request) -> {})
                .andRespond(MockRestResponseCreators.withSuccess(buildPaginatedPayload(100, "p1", 150), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), (ClientHttpRequest request) -> {})
                .andRespond(MockRestResponseCreators.withSuccess(buildPaginatedPayload(50, "p2", 150), MediaType.APPLICATION_JSON));

        VijaySalesDiscoveryProvider provider = new VijaySalesDiscoveryProvider(
                builder.build(), 100, 10000, 10000, 5);

        List<DiscoveredProduct> results = provider.discover("Samsung mobiles");

        assertEquals(150, results.size());
        server.verify();
    }

    /** B: Discovery stops when there are no more results. */
    @Test
    void discoveryStopsWhenNoMoreResults() throws Exception {
        RestClient.Builder builder = testRestClient();
        MockRestServiceServer server = MockRestServiceServer.createServer(builder);
        server.expect(ExpectedCount.once(), (ClientHttpRequest request) -> {})
                .andRespond(MockRestResponseCreators.withSuccess(buildPaginatedPayload(100, "p1", 150), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), (ClientHttpRequest request) -> {})
                .andRespond(MockRestResponseCreators.withSuccess(buildPaginatedPayload(0, "p2", 0), MediaType.APPLICATION_JSON));

        VijaySalesDiscoveryProvider provider = new VijaySalesDiscoveryProvider(
                builder.build(), 100, 10000, 10000, 5);

        List<DiscoveredProduct> results = provider.discover("Samsung mobiles");

        assertEquals(100, results.size());
        server.verify();
    }

    /**
     * Discovery continues through the full catalogue (no arbitrary limit)
     * and stops when totalCount is fully consumed.
     */
    @Test
    void discoveryContinuesThroughFullCatalogue() throws Exception {
        RestClient.Builder builder = testRestClient();
        MockRestServiceServer server = MockRestServiceServer.createServer(builder);
        server.expect(ExpectedCount.once(), (ClientHttpRequest request) -> {})
                .andRespond(MockRestResponseCreators.withSuccess(buildPaginatedPayload(100, "p1", 150), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), (ClientHttpRequest request) -> {})
                .andRespond(MockRestResponseCreators.withSuccess(buildPaginatedPayload(50, "p2", 150), MediaType.APPLICATION_JSON));

        VijaySalesDiscoveryProvider provider = new VijaySalesDiscoveryProvider(
                builder.build(), 100, 10000, 10000, 5);

        List<DiscoveredProduct> results = provider.discover("Samsung mobiles");

        assertEquals(150, results.size());
        server.verify();
    }

    /**
     * Discovery stops when an empty page is returned (source signals no more data).
     */
    @Test
    void discoveryStopsWhenSourceReturnsEmptyPage() throws Exception {
        RestClient.Builder builder = testRestClient();
        MockRestServiceServer server = MockRestServiceServer.createServer(builder);
        server.expect(ExpectedCount.once(), (ClientHttpRequest request) -> {})
                .andRespond(MockRestResponseCreators.withSuccess(buildPaginatedPayload(100, "p1", -1), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), (ClientHttpRequest request) -> {})
                .andRespond(MockRestResponseCreators.withSuccess(buildPaginatedPayload(0, "p2", -1), MediaType.APPLICATION_JSON));

        VijaySalesDiscoveryProvider provider = new VijaySalesDiscoveryProvider(
                builder.build(), 100, 10000, 10000, 2);

        List<DiscoveredProduct> results = provider.discover("Samsung mobiles");

        assertEquals(100, results.size());
        server.verify();
    }

    /** D2: Discovery continues beyond maxPages when totalCount is known. */
    @Test
    void discoveryContinuesBeyondMaxPagesWhenTotalCountKnown() throws Exception {
        RestClient.Builder builder = testRestClient();
        MockRestServiceServer server = MockRestServiceServer.createServer(builder);
        server.expect(ExpectedCount.once(), (ClientHttpRequest request) -> {})
                .andRespond(MockRestResponseCreators.withSuccess(buildPaginatedPayload(100, "p1", 250), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), (ClientHttpRequest request) -> {})
                .andRespond(MockRestResponseCreators.withSuccess(buildPaginatedPayload(100, "p2", 250), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), (ClientHttpRequest request) -> {})
                .andRespond(MockRestResponseCreators.withSuccess(buildPaginatedPayload(50, "p3", 250), MediaType.APPLICATION_JSON));

        VijaySalesDiscoveryProvider provider = new VijaySalesDiscoveryProvider(
                builder.build(), 100, 10000, 10000, 2);

        List<DiscoveredProduct> results = provider.discover("Samsung mobiles");

        assertEquals(250, results.size());
        server.verify();
    }

    /** E: Duplicate retailer URLs are not returned multiple times across pages. */
    @Test
    void duplicateUrlsAreRemovedAcrossPages() throws Exception {
        String dupUrl = "https://www.vijaysales.com/p/DUP-sku/dup-product";
        RestClient.Builder builder = testRestClient();
        MockRestServiceServer server = MockRestServiceServer.createServer(builder);
        String page1 = buildPaginatedPayload(100, "p1", 1001)
                .replace("]", "," + item("Dup Product", "DUP-sku", dupUrl, "IN_STOCK") + "]");
        String page2 = buildPaginatedPayload(50, "p2", 1000)
                .replace("]", "," + item("Dup Product Again", "DUP-sku", dupUrl, "IN_STOCK") + "]");
        server.expect(ExpectedCount.once(), (ClientHttpRequest request) -> {})
                .andRespond(MockRestResponseCreators.withSuccess(page1, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), (ClientHttpRequest request) -> {})
                .andRespond(MockRestResponseCreators.withSuccess(page2, MediaType.APPLICATION_JSON));

        VijaySalesDiscoveryProvider provider = new VijaySalesDiscoveryProvider(
                builder.build(), 100, 10000, 10000, 5);

        List<DiscoveredProduct> results = provider.discover("Samsung mobiles");

        long dupCount = results.stream().filter(r -> r.productUrl().equals(dupUrl)).count();
        assertEquals(1, dupCount, "Duplicate URL should appear exactly once");
        assertEquals(151, results.size(), "151 unique products expected after dedup across 2 pages");
        server.verify();
    }

    /** F: Different legitimate variants with different SKUs are preserved. */
    @Test
    void differentVariantsWithDifferentSkusArePreserved() {
        String json = payload(
                item("Samsung Galaxy M36 6GB/128GB", "P251081", "/p/P251081/samsung-galaxy-m36-6-128", "IN_STOCK") + ","
                        + item("Samsung Galaxy M36 8GB/128GB", "P251082", "/p/P251082/samsung-galaxy-m36-8-128", "IN_STOCK") + ","
                        + item("Samsung Galaxy M36 8GB/256GB", "P251083", "/p/P251083/samsung-galaxy-m36-8-256", "IN_STOCK"));

        var result = provider.parseResponse(json, 10);
        assertEquals(3, result.size());
        assertEquals("P251081", result.get(0).sku());
        assertEquals("P251082", result.get(1).sku());
        assertEquals("P251083", result.get(2).sku());
    }

    /** G: Real Vijay Sales image URLs with query parameters are preserved. */
    @Test
    void realImageUrlWithQueryParametersIsPreserved() {
        String imageUrl = "https://vsprod.vijaysales.com/media/catalog/product/2/5/259187_1_.jpg?optimize=medium&fit=bounds&height=&width=";
        String json = payload(itemWithImage("Samsung TV", "259187", "/p/259187/samsung-tv", "IN_STOCK", imageUrl));

        var result = provider.parseResponse(json, 10);
        assertEquals(1, result.size());
        assertEquals(imageUrl, result.get(0).imageUrl());
    }

    /** G: Logo/placeholder/brand images are rejected. */
    @Test
    void logoAndPlaceholderImagesAreRejected() {
        String json = payload(
                itemWithImage("Samsung TV Logo", "1", "/p/1/logo", "IN_STOCK", "https://cdn.vijaysales.com/img/logo.png") + ","
                        + itemWithImage("TV Brand Image", "2", "/p/2/brand", "IN_STOCK", "https://cdn.vijaysales.com/img/brand.jpg") + ","
                        + itemWithImage("TV Icon", "3", "/p/3/icon", "IN_STOCK", "https://cdn.vijaysales.com/img/icon.svg") + ","
                        + itemWithImage("TV Real Product", "4", "/p/4/product", "IN_STOCK", "https://cdn.vijaysales.com/img/259187_1_.jpg"));

        var result = provider.parseResponse(json, 10);
        assertEquals(4, result.size(), "All candidates should be parsed");
        for (DiscoveredProduct dp : result) {
            if ("4".equals(dp.sku())) {
                assertEquals("https://cdn.vijaysales.com/img/259187_1_.jpg", dp.imageUrl(),
                        "Real product should preserve image URL");
            } else {
                assertTrue(dp.imageUrl().isBlank(),
                        "Non-product image should be rejected for SKU " + dp.sku());
            }
        }
    }

    /** G: GraphQL query does not request the invalid category field. */
    @Test
    void graphqlQueryDoesNotRequestCategoryField() {
        String query = VijaySalesDiscoveryProvider.buildGraphqlQuery("tv", 100, 1);
        assertFalse(query.contains("category"),
                "GraphQL query must not request the unsupported category field");
        assertTrue(query.contains("name"), "GraphQL query should request product name");
        assertTrue(query.contains("sku"), "GraphQL query should request SKU");
        assertTrue(query.contains("product_url"), "GraphQL query should request product URL");
    }

    private static String itemWithImage(String name, String sku, String url, String stock, String imageUrl) {
        return "{\"name\":\"" + name + "\",\"sku\":\"" + sku + "\",\"product_url\":\""
                + url + "\",\"stock_status\":\"" + stock + "\",\"mrp\":\"20990\","
                + "\"image\":{\"url\":\"" + imageUrl + "\"}}";
    }
}
