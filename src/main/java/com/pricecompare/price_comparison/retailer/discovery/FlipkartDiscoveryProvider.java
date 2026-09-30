package com.pricecompare.price_comparison.retailer.discovery;

import com.pricecompare.price_comparison.retailer.RetailerConfigurationException;
import com.pricecompare.price_comparison.retailer.RetailerException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Flipkart discovery provider (Phase 7) built on the OFFICIAL Flipkart
 * Affiliate API (v1.0 search.json) — a permitted, documented API. No HTML
 * scraping and no CAPTCHA/anti-bot interaction of any kind.
 *
 * <p>CREDENTIALS: the affiliate id/token are read from configuration sourced
 * from environment variables. When they are absent, discovery raises
 * {@link RetailerConfigurationException} so orchestration layers report an
 * explicit CONFIGURATION_REQUIRED status — no credentials are invented and
 * no prices are fabricated.</p>
 *
 * <p>Responses are mapped defensively into the common normalized
 * {@link DiscoveredProduct} model (productId as SKU, title, price/MRP,
 * availability, product URL, image). Failures, timeouts, rate limits and
 * malformed payloads raise controlled {@link RetailerException}s; one
 * retailer failing never affects the others.</p>
 */
@Component
public class FlipkartDiscoveryProvider implements ProductDiscoveryProvider {

    static final String SEARCH_URL = "https://affiliate-api.flipkart.net/affiliate/1.0/search.json";
    private static final String PRODUCT_HOST = "www.flipkart.com";

    private final String affiliateId;
    private final String affiliateToken;
    private final int maxCandidates;
    private final RestClient restClient;
    // Jackson 3 (Spring Boot 4 default); parsing is network-free and deterministic.
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public FlipkartDiscoveryProvider(
            @Value("${flipkart.affiliate.id:}") String affiliateId,
            @Value("${flipkart.affiliate.token:}") String affiliateToken,
            @Value("${app.retailers.flipkart.search-result-count:5}") int maxCandidates) {
        this.affiliateId = affiliateId == null ? "" : affiliateId.trim();
        this.affiliateToken = affiliateToken == null ? "" : affiliateToken.trim();
        this.maxCandidates = Math.max(1, maxCandidates);
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
    public List<DiscoveredProduct> discover(String query) {
        String search = normalizeQuery(query);
        if (search.isEmpty()) {
            return List.of();
        }
        requireCredentials();
        URI uri = URI.create(SEARCH_URL + "?query="
                + URLEncoder.encode(search, StandardCharsets.UTF_8)
                + "&resultCount=" + maxCandidates);
        String body;
        try {
            body = restClient.get()
                    .uri(uri)
                    .header("Fk-Affiliate-Id", affiliateId)
                    .header("Fk-Affiliate-Token", affiliateToken)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(String.class);
        } catch (Exception ex) {
            throw new RetailerException("Flipkart discovery request failed: " + ex.getMessage(), ex);
        }
        if (body == null || body.isBlank()) {
            throw new RetailerException("Flipkart discovery returned an empty response.");
        }
        return parseResponse(body, maxCandidates);
    }

    private void requireCredentials() {
        if (!isConfigured()) {
            throw new RetailerConfigurationException(
                    "Flipkart Affiliate API credentials are not configured "
                            + "(set FLIPKART_AFFILIATE_ID and FLIPKART_AFFILIATE_TOKEN). "
                            + "No Flipkart data can be fetched until they are provided.");
        }
    }

    /**
     * Network-free normalization of an Affiliate search payload. Defensive by
     * design: Affiliate payloads nest values under several shapes, so every
     * field is read with fallbacks and anything unmappable is skipped.
     */
    List<DiscoveredProduct> parseResponse(String json, int limit) {
        JsonNode items;
        try {
            items = objectMapper.readTree(json).path("productInfoList");
        } catch (Exception ex) {
            throw new RetailerException("Flipkart discovery response could not be parsed.", ex);
        }
        if (!items.isArray() || items.isEmpty()) {
            return List.of();
        }
        List<DiscoveredProduct> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode wrapper : items) {
            if (out.size() >= limit) {
                break;
            }
            JsonNode item = wrapper.path("productBaseInfoV1");
            if (item.isMissingNode() || item.isNull()) {
                continue;
            }
            DiscoveredProduct candidate = toCandidate(item);
            if (candidate == null) {
                continue;
            }
            if (seen.add(DiscoveredProduct.dedupeKey(candidate.productUrl()))) {
                out.add(candidate);
            }
        }
        return List.copyOf(out);
    }

    private DiscoveredProduct toCandidate(JsonNode item) {
        String url = item.path("productUrl").asText("").trim();
        if (!isTrackableProductUrl(url)) {
            return null;
        }
        String title = item.path("title").asText("").trim();
        if (title.isEmpty()) {
            return null; // nothing to display or identity-validate later
        }
        return new DiscoveredProduct(
                RetailerKey.FLIPKART,
                url,
                title,
                item.path("productId").asText("").trim(),
                firstText(item.path("imageUrls")),
                mrpText(item),
                mapAvailability(item.path("availability").asText("")),
                priceText(item),
                "INR");
    }

    /** imageUrls is an array in Affiliate payloads; take the first entry. */
    private static String firstText(JsonNode arrayNode) {
        if (arrayNode.isArray() && !arrayNode.isEmpty()) {
            return arrayNode.get(0).asText("");
        }
        return "";
    }

    /**
     * Price fields vary across Affiliate payload versions; try the known
     * locations in order and fall back to "" rather than guessing.
     */
    private static String priceText(JsonNode item) {
        JsonNode pricing = item.path("pricing");
        if (pricing.hasNonNull("finalPrice")) {
            return numberOrText(pricing.path("finalPrice"));
        }
        return numberOrText(item.path("flipkartSellingPrice"));
    }

    private static String mrpText(JsonNode item) {
        JsonNode pricing = item.path("pricing");
        if (pricing.hasNonNull("mrp")) {
            return numberOrText(pricing.path("mrp"));
        }
        return numberOrText(item.path("maximumRetailPrice"));
    }

    private static String numberOrText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isNumber()) {
            return node.decimalValue().toPlainString();
        }
        return node.asText("").trim();
    }

    /** "available"/"unavailable"/"marketplace" Affiliate values -> normalized text. */
    static String mapAvailability(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "available" -> "In stock";
            case "unavailable" -> "Out of stock";
            case "marketplace" -> "Marketplace seller";
            default -> "";
        };
    }

    /**
     * Flipkart product pages only: http/https on the Flipkart host whose path
     * contains the /p/ product marker. Refuses every other host (SSRF-safe)
     * and non-product pages. Public so link validation can reuse it.
     */
    public static boolean isTrackableProductUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || !PRODUCT_HOST.equalsIgnoreCase(host)) {
                return false;
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            return path.contains("/p/");
        } catch (Exception ex) {
            return false;
        }
    }

    static String normalizeQuery(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[\"\\\\\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
