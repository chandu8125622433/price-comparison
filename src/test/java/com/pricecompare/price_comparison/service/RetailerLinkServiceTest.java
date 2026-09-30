package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.dto.RetailerLinkRequest;
import com.pricecompare.price_comparison.dto.RetailerLinkResponse;
import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.entity.RetailerLink;
import com.pricecompare.price_comparison.exception.ResourceNotFoundException;
import com.pricecompare.price_comparison.repository.RetailerLinkRepository;
import com.pricecompare.price_comparison.retailer.ProductIdentityMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deterministic unit tests for the Phase 4 candidate-confirmation flow.
 *
 * <p>Covers the linking contract at the service level with the REAL
 * {@link ProductIdentityMatcher} (conservative by design, no network):
 * product existence, retailer validation, Vijay Sales URL validation
 * (host allowlist + /p/{sku}/{slug} shape), the fail-closed identity gate
 * at confirmation time, SKU preservation/normalization, idempotent
 * re-confirmation and replacement of the active link.</p>
 */
class RetailerLinkServiceTest {

    private static final String SAMSUNG_URL =
            "https://www.vijaysales.com/p/258479/samsung-139-cm-55-inch-4k-ultra-hd-smart-tv";
    private static final String SAMSUNG_TITLE =
            "Samsung 139 cm (55 inch) 4K Ultra HD Smart LED TV UA55";
    private static final String SAMSUNG_SKU = "258479";
    private static final String LG_URL =
            "https://www.vijaysales.com/p/238476/lg-80-cm-32-inch-hd-ai-smart-webos-led-tv";

    private ProductService productService;
    private RetailerLinkRepository linkRepository;
    private RetailerLinkService service;
    private Product product;

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        linkRepository = mock(RetailerLinkRepository.class);
        service = new RetailerLinkService(
                productService, linkRepository, new ProductIdentityMatcher());

        product = new Product("Samsung 55 inch 4K Smart TV", "Samsung", "UA55", "Television");
        when(productService.requireProduct(1L)).thenReturn(product);
        when(productService.requireProduct(99L))
                .thenThrow(new ResourceNotFoundException("Product not found with id: 99"));
        when(linkRepository.findByProductIdAndWebsite(anyLong(), anyString()))
                .thenReturn(Optional.empty());
    }

    private static RetailerLinkRequest samsungCandidate() {
        return new RetailerLinkRequest("VIJAY_SALES", SAMSUNG_URL, SAMSUNG_SKU, SAMSUNG_TITLE);
    }

    /* 1. Product does not exist -> controlled error, nothing stored. */
    @Test
    void confirmRejectsMissingProduct() {
        assertThrows(ResourceNotFoundException.class,
                () -> service.confirmLink(99L, samsungCandidate()));
        verify(linkRepository, never()).save(any());
    }

    /* 2a. Unknown retailer value -> rejected. */
    @Test
    void confirmRejectsUnknownRetailer() {
        RetailerLinkRequest request =
                new RetailerLinkRequest("FooMart", SAMSUNG_URL, SAMSUNG_SKU, SAMSUNG_TITLE);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.confirmLink(1L, request));
        assertTrue(ex.getMessage().contains("Unknown retailer"));
        verify(linkRepository, never()).save(any());
    }

    /* 2b. Known retailer without an implemented validator -> rejected. */
    @Test
    void confirmRejectsUnsupportedRetailer() {
        RetailerLinkRequest request =
                new RetailerLinkRequest("AMAZON", "https://www.amazon.in/p/1/x", "1", SAMSUNG_TITLE);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.confirmLink(1L, request));
        assertTrue(ex.getMessage().contains("not supported"));
        verify(linkRepository, never()).save(any());
    }

    /* 3. Blank/empty product URL -> rejected. */
    @Test
    void confirmRejectsBlankProductUrl() {
        RetailerLinkRequest request =
                new RetailerLinkRequest("VIJAY_SALES", "   ", SAMSUNG_SKU, SAMSUNG_TITLE);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.confirmLink(1L, request));
        assertTrue(ex.getMessage().contains("must not be empty"));
        verify(linkRepository, never()).save(any());
    }

    /* 4. Non-product URLs and foreign/private/malformed hosts -> rejected. */
    @Test
    void confirmRejectsInvalidVijaySalesUrls() {
        String[] badUrls = {
                "https://www.vijaysales.com/c/televisions",              // category page
                "https://www.vijaysales.com/search?q=samsung%20tv",      // search page
                "https://www.vijaysales.com/p/238476",                   // /p/{sku} only
                "https://www.amazon.in/p/238476/lg-tv",                  // foreign host
                "https://localhost/p/238476/lg-tv",                      // private host
                "javascript:alert(1)",                                   // script URL
                "file:///C:/tmp/lg.html",                                // file URL
                "ht!tp://broken"                                         // malformed
        };
        for (String bad : badUrls) {
            RetailerLinkRequest request =
                    new RetailerLinkRequest("VIJAY_SALES", bad, SAMSUNG_SKU, SAMSUNG_TITLE);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.confirmLink(1L, request),
                    "expected rejection for: " + bad);
            assertTrue(ex.getMessage().contains("not a valid Vijay Sales product page"),
                    "unexpected message for " + bad + ": " + ex.getMessage());
        }
        verify(linkRepository, never()).save(any());
    }

    /* 4b. A null URL is caught by the blank-URL guard before validation. */
    @Test
    void confirmRejectsNullProductUrlAsEmpty() {
        RetailerLinkRequest request =
                new RetailerLinkRequest("VIJAY_SALES", null, SAMSUNG_SKU, SAMSUNG_TITLE);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.confirmLink(1L, request));
        assertTrue(ex.getMessage().contains("must not be empty"));
        verify(linkRepository, never()).save(any());
    }

    /* 5. Valid candidate -> linked; URL/SKU/retailer persisted; clear response. */
    @Test
    void confirmLinksValidCandidateAndPreservesSku() {
        RetailerLinkResponse response = service.confirmLink(1L, samsungCandidate());

        assertEquals(1L, response.productId());
        assertEquals("Vijay Sales", response.retailer());
        assertEquals(SAMSUNG_URL, response.productUrl());
        assertEquals(SAMSUNG_SKU, response.sku());
        assertEquals("linked", response.status());

        ArgumentCaptor<RetailerLink> captor = ArgumentCaptor.forClass(RetailerLink.class);
        verify(linkRepository).save(captor.capture());
        RetailerLink saved = captor.getValue();
        assertEquals(product, saved.getProduct());
        assertEquals("Vijay Sales", saved.getWebsite());
        assertEquals(SAMSUNG_URL, saved.getProductUrl());
        assertEquals(SAMSUNG_SKU, saved.getSku());
    }

    /* 6. Blank SKU is normalized so a link never stores an empty SKU. */
    @Test
    void confirmNormalizesBlankSkuToNull() {
        RetailerLinkRequest request =
                new RetailerLinkRequest("VIJAY_SALES", SAMSUNG_URL, "   ", SAMSUNG_TITLE);
        RetailerLinkResponse response = service.confirmLink(1L, request);

        assertEquals("linked", response.status());
        ArgumentCaptor<RetailerLink> captor = ArgumentCaptor.forClass(RetailerLink.class);
        verify(linkRepository).save(captor.capture());
        assertNull(captor.getValue().getSku());
    }

    /* 7. Re-confirming the identical candidate -> idempotent no-op. */
    @Test
    void duplicateConfirmationIsIdempotentNoOp() {
        RetailerLink existing =
                new RetailerLink(product, "Vijay Sales", SAMSUNG_URL, SAMSUNG_SKU);
        when(linkRepository.findByProductIdAndWebsite(1L, "Vijay Sales"))
                .thenReturn(Optional.of(existing));

        RetailerLinkResponse response = service.confirmLink(1L, samsungCandidate());

        assertEquals("already-linked", response.status());
        assertEquals(SAMSUNG_URL, response.productUrl());
        verify(linkRepository, never()).save(any());
    }

    /* 8. A different confirmed candidate REPLACES the active link (no dup). */
    @Test
    void existingLinkIsReplacedByNewCandidate() {
        RetailerLink existing = new RetailerLink(product, "Vijay Sales", LG_URL, "238476");
        when(linkRepository.findByProductIdAndWebsite(1L, "Vijay Sales"))
                .thenReturn(Optional.of(existing));

        RetailerLinkResponse response = service.confirmLink(1L, samsungCandidate());

        assertEquals("updated", response.status());
        ArgumentCaptor<RetailerLink> captor = ArgumentCaptor.forClass(RetailerLink.class);
        verify(linkRepository).save(captor.capture());
        RetailerLink saved = captor.getValue();
        assertEquals(existing, saved);                       // same row updated, not duplicated
        assertEquals(SAMSUNG_URL, saved.getProductUrl());    // new URL is active
        assertEquals(SAMSUNG_SKU, saved.getSku());           // new SKU is active
    }

    /* 9. Wrong product (Samsung requested, Xiaomi candidate) -> rejected. */
    @Test
    void candidateWithWrongIdentityIsRejected() {
        RetailerLinkRequest request = new RetailerLinkRequest(
                "VIJAY_SALES",
                "https://www.vijaysales.com/p/245223/xiaomi-a-pro-qled-series-80cm-32-inch-hd-google-tv",
                "245223",
                "Xiaomi A Pro QLED Series 80cm (32 inch) HD Google TV");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.confirmLink(1L, request));
        assertTrue(ex.getMessage().contains("mismatch"), "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Samsung 55 inch 4K Smart TV"));
        verify(linkRepository, never()).save(any());
    }

    /* 10. Missing candidate title -> fail-closed at the identity gate. */
    @Test
    void candidateWithoutTitleIsRejectedFailClosed() {
        RetailerLinkRequest request =
                new RetailerLinkRequest("VIJAY_SALES", SAMSUNG_URL, SAMSUNG_SKU, "  ");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.confirmLink(1L, request));
        assertTrue(ex.getMessage().contains("productTitle is required"));
        verify(linkRepository, never()).save(any());
    }
}

