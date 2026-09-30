package com.pricecompare.price_comparison.retailer.provider;

import com.pricecompare.price_comparison.retailer.RetailerConfigurationException;
import com.pricecompare.price_comparison.retailer.RetailerException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.retailer.RetailerPrice;
import com.pricecompare.price_comparison.retailer.RetailerPriceProvider;
import com.pricecompare.price_comparison.retailer.discovery.FlipkartDiscoveryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Flipkart price provider (Phase 7) on the OFFICIAL Flipkart Affiliate API
 * (v1.0 product.json). No HTML scraping, no CAPTCHA/anti-bot interaction.
 *
 * <p>CREDENTIALS: read from configuration sourced from environment variables.
 * When absent, {@link #fetchPrice(String)} raises
 * {@link RetailerConfigurationException} — the refresh orchestrator reports
 * Flipkart as failed/configuration-required while other retailers continue.
 * No prices are ever fabricated.</p>
 *
 * <p>Only already-confirmed Flipkart product URLs are honored, and only after
 * host validation; the URL's {@code pid} query parameter maps it to the
 * Affiliate product endpoint (a URL without a pid cannot be resolved).</p>
 */
@Component
public class FlipkartRetailerPriceProvider implements RetailerPriceProvider {

    static final String PRODUCT_URL = "https://affiliate-api.flipkart.net/affiliate/1.0/product.json";

    private final String affiliateId;
    private final String affiliateToken;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public FlipkartRetailerPriceProvider(
            @Value("${flipkart.affiliate.id:}") String affiliateId,
            @Value("${flipkart.affiliate.token:}") String affiliateToken) {
        this.affiliateId = affiliateId == null ? "" : affiliateId.trim();
        this.affiliateToken = affiliateToken == null ? "" : affiliateToken.trim();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public RetailerKey retailer() {
        return RetailerKey.FLIPKART;
    }

    boolean isConfigured() {
        return !affiliateId.isEmpty() && !affiliateToken.isEmpty();
    }

    @Override
    public List<RetailerPrice> fetchPrice(String productUrl) {
        requireCredentials();
        if (productUrl == null || productUrl.isBlank()
                || !FlipkartDiscoveryProvider.isTrackableProductUrl(productUrl)) {
            throw new RetailerException("URL is not a valid Flipkart product page: " + productUrl);
        }
        String pid = extractPid(productUrl);
        if (pid.isBlank()) {
            throw new RetailerException(
                    "Flipkart URL does not expose a pid parameter; cannot resolve it through "
                            + "the Affiliate product API: " + productUrl);
        }
        String body;
        try {
            body = restClient.get()
                    .uri(URI.create(PRODUCT_URL + "?id=" + pid))
                    .header("Fk-Affiliate-Id", affiliateId)
                    .header("Fk-Affiliate-Token", affiliateToken)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(String.class);
        } catch (Exception ex) {
            throw new RetailerException("Flipkart product fetch failed: " + ex.getMessage(), ex);
        }
        if (body == null || body.isBlank()) {
            throw new RetailerException("Flipkart product response was empty.");
        }
        return parseResponse(body, productUrl);
    }

    private void requireCredentials() {
        if (!isConfigured()) {
            throw new RetailerConfigurationException(
                    "Flipkart Affiliate API credentials are not configured "
                            + "(set FLIPKART_AFFILIATE_ID and FLIPKART_AFFILIATE_TOKEN). "
                            + "No Flipkart price can be fetched until they are provided.");
        }
    }

    /** Extracts the pid query parameter (the Affiliate product identifier). */
    static String extractPid(String productUrl) {
        try {
            URI uri = URI.create(productUrl.trim());
            String query = uri.getQuery() == null ? "" : uri.getQuery();
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "pid".equalsIgnoreCase(pair.substring(0, eq))) {
                    return pair.substring(eq + 1).trim();
                }
            }
            return "";
        } catch (Exception ex) {
            return "";
        }
    }

    /** Network-free normalization of an Affiliate product payload. */
    List<RetailerPrice> parseResponse(String json, String requestedUrl) {
        JsonNode base;
        try {
            base = objectMapper.readTree(json).path("productBaseInfoV1");
        } catch (Exception ex) {
            throw new RetailerException("Flipkart product response could not be parsed.", ex);
        }
        if (base.isMissingNode() || base.isNull()) {
            throw new RetailerException("Flipkart product response contained no product data.");
        }
        String title = base.path("title").asText("").trim();
        String productId = base.path("productId").asText("").trim();
        BigDecimal price = parseAmount(priceText(base));
        if (price == null) {
            throw new RetailerException("Flipkart product has no usable selling price: "
                    + (title.isBlank() ? "(no title)" : title));
        }
        String url = base.path("productUrl").asText("").trim();
        return List.of(new RetailerPrice(
                RetailerKey.FLIPKART,
                price,
                url.isBlank() ? requestedUrl : url,
                title,
                LocalDateTime.now(),
                productId));
    }

    private static String priceText(JsonNode base) {
        JsonNode pricing = base.path("pricing");
        if (pricing.hasNonNull("finalPrice")) {
            return pricing.path("finalPrice").asText("");
        }
        return base.path("flipkartSellingPrice").asText("");
    }

    /**
     * Controlled amount parsing: null/blank/"null"/malformed -> null, so the
     * caller raises a controlled RetailerException instead of crashing.
     */
    static BigDecimal parseAmount(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim();
        if (cleaned.isEmpty() || cleaned.equalsIgnoreCase("null") || cleaned.equals("-")) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(cleaned.replaceAll("[^0-9.]", ""));
            return value.signum() > 0 ? value : null;
        } catch (Exception ex) {
            return null;
        }
    }
}

