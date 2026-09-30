package com.pricecompare.price_comparison.retailer.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Focused, network-free tests for the Reliance Digital discovery provider:
 * sitemap/HTML parsing, slug filtering, URL safety and candidate data
 * normalization. No live website access.
 */
class RelianceDigitalDiscoveryProviderTest {

    private static final String INDEX_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sitemapindex>
              <sitemap><loc>https://www.reliancedigital.in/sitemap/products/1/page.sitemap.xml</loc></sitemap>
              <sitemap><loc>https://www.reliancedigital.in/sitemap/products/2/page.sitemap.xml</loc></sitemap>
              <sitemap><loc>https://www.reliancedigital.in/sitemap/brand.sitemap.xml</loc></sitemap>
            </sitemapindex>
            """;

    private static final String PRODUCT_PAGE_HTML = """
            <html><head><title>x</title></head><body>
            <h1> LG 1.5 Ton 5 Star Inverter Split AC </h1>
            <script type="application/ld+json">{"@context":"http://schema.org","@type":"Product",
              "name":"LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)",
              "url":"https://www.reliancedigital.in/product/lg-1-5-ton-5-star-inverter-split-ac-as-q20enze-9000002",
              "offers":{"@type":"Offer","priceCurrency":"INR","price":"45490",
                        "availability":"http://schema.org/InStock"}}</script>
            <script type="application/ld+json">{"@type":"FAQPage","mainEntity":[]}</script>
            </body></html>
            """;

    @Test
    void extractLocsParsesSitemapLocEntries() {
        List<String> locs = RelianceDigitalDiscoveryProvider.extractLocs(INDEX_XML);
        assertEquals(3, locs.size());
        assertTrue(locs.get(0).endsWith("/sitemap/products/1/page.sitemap.xml"));
    }

    @Test
    void extractLocsHandlesBlankAndNullXml() {
        assertTrue(RelianceDigitalDiscoveryProvider.extractLocs(null).isEmpty());
        assertTrue(RelianceDigitalDiscoveryProvider.extractLocs("   ").isEmpty());
    }

    @Test
    void slugChunksNormalizeQueriesAndUrls() {
        Set<String> chunks = RelianceDigitalDiscoveryProvider.slugChunks("LG 1.5 Ton 5 Star Split AC");
        assertTrue(chunks.containsAll(Set.of("lg", "1", "5", "ton", "star", "split", "ac")));
        assertTrue(RelianceDigitalDiscoveryProvider.slugChunks(null).isEmpty());
        assertTrue(RelianceDigitalDiscoveryProvider.slugChunks("   ").isEmpty());
    }

    @Test
    void trackableUrlAcceptsOnlyRelianceDigitalProductPagesWithoutQueryString() {
        assertTrue(RelianceDigitalDiscoveryProvider.isTrackableProductUrl(
                "https://www.reliancedigital.in/product/lg-1-5-ton-5-star-split-ac-as-q20enze-9000002"));
        assertTrue(RelianceDigitalDiscoveryProvider.isTrackableProductUrl(
                "https://reliancedigital.in/product/lg-1-5-ton-5-star-split-ac-9000002"));
        // robots.txt disallows query-string URLs: our fetches never use one.
        assertFalse(RelianceDigitalDiscoveryProvider.isTrackableProductUrl(
                "https://www.reliancedigital.in/product/lg-split-ac?searchQuery=x"));
        assertFalse(RelianceDigitalDiscoveryProvider.isTrackableProductUrl(
                "https://www.some-other-host.com/product/lg-1-5-ton-5-star-split-ac-1111111"));
        assertFalse(RelianceDigitalDiscoveryProvider.isTrackableProductUrl(
                "https://www.reliancedigital.in/c/air-conditioners"));
        assertFalse(RelianceDigitalDiscoveryProvider.isTrackableProductUrl(null));
        assertFalse(RelianceDigitalDiscoveryProvider.isTrackableProductUrl("   "));
    }

    @Test
    void parseProductPageExtractsPriceTitleSkuFromJsonLd() {
        var prices = RelianceDigitalDiscoveryProvider.parseProductPage(PRODUCT_PAGE_HTML,
                "https://www.reliancedigital.in/product/lg-1-5-ton-5-star-inverter-split-ac-as-q20enze-9000002");
        assertEquals(1, prices.size());
        assertEquals("45490", prices.get(0).price().toPlainString());
        assertEquals("LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)",
                prices.get(0).productTitle());
        assertEquals("9000002", prices.get(0).sku());
        assertEquals("In stock", RelianceDigitalDiscoveryProvider.availabilityOf(PRODUCT_PAGE_HTML));
    }

    @Test
    void parseProductPageFallsBackToH1WhenJsonLdNameIsMissing() {
        String html = """
                <html><body><h1>LG Split AC Fallback Title</h1>
                <script type="application/ld+json">{"@type":"Product",
                  "offers":{"price":"42990"}}</script></body></html>
                """;
        var prices = RelianceDigitalDiscoveryProvider.parseProductPage(html,
                "https://www.reliancedigital.in/product/lg-1-5-ton-5-star-inverter-split-ac-as-q19cnze1-9000001");
        assertEquals("LG Split AC Fallback Title", prices.get(0).productTitle());
        assertEquals("42990", prices.get(0).price().toPlainString());
    }

    @Test
    void parseProductPageRejectsMissingPrice() {
        String html = """
                <html><body><h1>No price here</h1>
                <script type="application/ld+json">{"@type":"FAQPage"}</script></body></html>
                """;
        assertThrows(Exception.class, () -> RelianceDigitalDiscoveryProvider.parseProductPage(
                html, "https://www.reliancedigital.in/product/lg-split-ac-9000001"));
    }

    @Test
    void parseProductPageRejectsBlankHtml() {
        assertThrows(Exception.class, () -> RelianceDigitalDiscoveryProvider.parseProductPage(
                "   ", "https://www.reliancedigital.in/product/lg-split-ac-9000001"));
    }

    @Test
    void skuFromUrlUsesTrailingNumericIdOnly() {
        assertEquals("9000002", RelianceDigitalDiscoveryProvider.skuFromUrl(
                "https://www.reliancedigital.in/product/lg-ac-as-q20enze-9000002"));
        assertEquals("", RelianceDigitalDiscoveryProvider.skuFromUrl(
                "https://www.reliancedigital.in/product/bpl-batten-bn20em0b-cdl"));
    }

    @Test
    void availabilityOfMapsSchemaOrgStates() {
        assertEquals("In stock", RelianceDigitalDiscoveryProvider.availabilityOf(
                "<script>{\"availability\":\"http://schema.org/InStock\"}</script>"));
        assertEquals("Out of stock", RelianceDigitalDiscoveryProvider.availabilityOf(
                "<script>{\"availability\":\"http://schema.org/OutOfStock\"}</script>"));
        assertEquals("", RelianceDigitalDiscoveryProvider.availabilityOf("<p>none</p>"));
    }
    /** Test image extraction from JSON-LD Product blocks. */
    @Test
    void firstImageUrlFromStringImageExtractsUrl() {
        String json = """
            {
              "@context": "http://schema.org",
              "@type": "Product",
              "name": "LG AC",
              "image": "https://cdn.reliancedigital.in/img/lg-ac-123456.jpg",
              "offers": {"price": "45490"}
            }
        """;
        String url = RelianceDigitalDiscoveryProvider.firstImageUrlFromPage(json);
        assertEquals("https://cdn.reliancedigital.in/img/lg-ac-123456.jpg", url);
    }

    @Test
    void firstImageUrlFromArrayOfStringsExtractsFirstUrl() {
        String json = """
            {
              "@context": "http://schema.org",
              "@type": "Product",
              "name": "LG AC",
              "image": ["https://cdn.reliancedigital.in/img/lg-ac-123456.jpg", "https://cdn.reliancedigital.in/img/lg-ac-789012.jpg"],
              "offers": {"price": "45490"}
            }
        """;
        String url = RelianceDigitalDiscoveryProvider.firstImageUrlFromPage(json);
        assertEquals("https://cdn.reliancedigital.in/img/lg-ac-123456.jpg", url);
    }

    @Test
    void firstImageUrlFromImageObjectArrayExtractsUrl() {
        String json = """
            {
              "@context": "http://schema.org",
              "@type": "Product",
              "name": "LG AC",
              "image": [{"@type": "ImageObject", "url": "https://cdn.reliancedigital.in/img/lg-ac-123456.jpg"}],
              "offers": {"price": "45490"}
            }
        """;
        String url = RelianceDigitalDiscoveryProvider.firstImageUrlFromPage(json);
        assertEquals("https://cdn.reliancedigital.in/img/lg-ac-123456.jpg", url);
    }

    @Test
    void firstImageUrlReturnsEmptyWhenNoImageField() {
        String json = """
            {
              "@context": "http://schema.org",
              "@type": "Product",
              "name": "LG AC",
              "offers": {"price": "45490"}
            }
        """;
        String url = RelianceDigitalDiscoveryProvider.firstImageUrlFromPage(json);
        assertEquals("", url);
    }

    @Test
    void firstImageUrlReturnsEmptyForBlankImage() {
        String json = """
            {
              "@context": "http://schema.org",
              "@type": "Product",
              "name": "LG AC",
              "image": "",
              "offers": {"price": "45490"}
            }
        """;
        String url = RelianceDigitalDiscoveryProvider.firstImageUrlFromPage(json);
        assertEquals("", url);
    }

    @Test
    void nonEmptyUrlRejectsLogoPlaceholderUrls() {
        assertNull(RelianceDigitalDiscoveryProvider.nonEmptyUrl("https://cdn.example.com/logo.png"));
        assertNull(RelianceDigitalDiscoveryProvider.nonEmptyUrl("https://cdn.example.com/brand-image.jpg"));
        assertNull(RelianceDigitalDiscoveryProvider.nonEmptyUrl("https://cdn.example.com/placeholder.gif"));
        assertNull(RelianceDigitalDiscoveryProvider.nonEmptyUrl("https://cdn.example.com/default-image.svg"));
    }

    @Test
    void nonEmptyUrlAcceptsGenuineProductImageUrls() {
        String url = RelianceDigitalDiscoveryProvider.nonEmptyUrl("https://cdn.reliancedigital.in/img/lg-ac-123456.jpg");
        assertEquals("https://cdn.reliancedigital.in/img/lg-ac-123456.jpg", url);
        url = RelianceDigitalDiscoveryProvider.nonEmptyUrl("https://images.example.com/product/abc123.png");
        assertEquals("https://images.example.com/product/abc123.png", url);
    }
}
