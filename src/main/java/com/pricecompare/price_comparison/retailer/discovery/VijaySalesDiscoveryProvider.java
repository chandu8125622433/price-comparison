package com.pricecompare.price_comparison.retailer.discovery;

import com.pricecompare.price_comparison.retailer.RetailerException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * Discovery provider for Vijay Sales (Phase 3 MVP scope).
 *
 * <p>Uses the retailer's own public, unauthenticated storefront search API
 * (the same GraphQL endpoint the website's normal frontend calls for every
 * visitor search) over plain HTTPS with standard browser identification
 * headers. No login, no CAPTCHA/anti-bot bypass, no robots circumvention:
 * if the endpoint refuses us, discovery fails with a controlled
 * {@link RetailerException} instead of working around the protection.</p>
 *
 * <p>Candidates are restricted to genuine product pages (http/https on
 * www.vijaysales.com with a /p/{sku}/{slug} path), de-duplicated and capped
 * at a small configurable limit. Category-independent by construction: the
 * storefront search spans every category the retailer sells.</p>
 */
@Component
public class VijaySalesDiscoveryProvider implements ProductDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(VijaySalesDiscoveryProvider.class);

    static final int DEFAULT_PAGE_SIZE = 100;
    static final int DEFAULT_SAFETY_LIMIT = 10000;
    static final int DEFAULT_MAX_PAGES = 5;

    private static final String GRAPHQL_URL = "https://vsprod.vijaysales.com/graphql";
    private static final String PRODUCT_HOST = "www.vijaysales.com";
    private static final String SITE_ROOT = "https://www.vijaysales.com";
    // Ordinary browser identification, consistent with SeleniumSession's
    // "fetch exactly as a normal browser would" stance. Not a bypass.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";

    private final RestClient restClient;
    // Jackson 3 (Spring Boot 4 default). Constructed internally: parsing is
    // a deterministic, network-free concern, not a bean dependency.
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final int pageSize;
    private final int safetyLimit;
    private final int maxCandidates;
    private final int maxPages;

    public VijaySalesDiscoveryProvider(@Value("${app.discovery.vijaysales.page-size:100}")
                                       int pageSize,
                                       @Value("${app.discovery.vijaysales.safety-limit:10000}")
                                       int safetyLimit) {
        this(buildRestClient(), pageSize, safetyLimit, safetyLimit, DEFAULT_MAX_PAGES);
    }

    @Autowired
    public VijaySalesDiscoveryProvider(@Value("${app.discovery.vijaysales.page-size:100}")
                                       int pageSize,
                                       @Value("${app.discovery.vijaysales.safety-limit:10000}")
                                       int safetyLimit,
                                       @Value("${app.discovery.vijaysales.max-candidates:10000}")
                                       int maxCandidates,
                                       @Value("${app.discovery.vijaysales.max-pages:5}")
                                       int maxPages) {
        this(buildRestClient(), pageSize, safetyLimit, maxCandidates, maxPages);
    }

    VijaySalesDiscoveryProvider(RestClient restClient, int pageSize, int safetyLimit,
                                    int maxCandidates, int maxPages) {
        this.restClient = restClient;
        this.pageSize = Math.max(1, pageSize);
        this.safetyLimit = Math.max(1, safetyLimit);
        this.maxCandidates = Math.max(1, Math.min(maxCandidates, this.safetyLimit));
        this.maxPages = Math.max(1, maxPages);
    }

    private static RestClient buildRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public RetailerKey retailer() {
        return RetailerKey.VIJAY_SALES;
    }

    @Override
    public List<DiscoveredProduct> discover(String query) {
        String search = normalizeQuery(query);
        if (search.isEmpty()) {
            return List.of();
        }
        List<DiscoveredProduct> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int currentPage = 1;
        int totalCount = -1;
        while (true) {
            String uri = GRAPHQL_URL + "?query="
                    + encodeQueryParam(buildGraphqlQuery(search, pageSize, currentPage));
            String body;
            try {
                body = restClient.get()
                        .uri(URI.create(uri))
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .header(HttpHeaders.USER_AGENT, USER_AGENT)
                        .retrieve()
                        .body(String.class);
            } catch (Exception ex) {
                throw new RetailerException(
                        "Vijay Sales discovery request failed: " + ex.getMessage(), ex);
            }
            if (body == null || body.isBlank()) {
                throw new RetailerException("Vijay Sales discovery returned an empty response.");
            }
            List<DiscoveredProduct> page = parseResponse(body, Integer.MAX_VALUE);
            if (page.isEmpty()) {
                break;
            }
            for (DiscoveredProduct candidate : page) {
                if (seen.add(DiscoveredProduct.dedupeKey(candidate.productUrl()))) {
                    all.add(candidate);
                }
            }
            if (totalCount < 0) {
                totalCount = parseTotalCount(body);
            }
            if (totalCount >= 0 && all.size() >= totalCount) {
                break;
            }
            if (page.size() < pageSize) {
                break;
            }
            currentPage++;
        }
        return List.copyOf(all);
    }

    @Override
    public int discoverTotalCount(String query) {
        String search = normalizeQuery(query);
        if (search.isEmpty()) {
            return -1;
        }
        String uri = GRAPHQL_URL + "?query="
                + encodeQueryParam(buildGraphqlQuery(search, 1, 1));
        try {
            String body = restClient.get()
                    .uri(URI.create(uri))
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .retrieve()
                    .body(String.class);
            if (body != null && !body.isBlank()) {
                return parseTotalCount(body);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return -1;
    }

    /*
     * Network-free parsing/validation of a storefront search payload.
     * Package-private so it can be unit-tested deterministically.
     */
    List<DiscoveredProduct> parseResponse(String json, int limit) {
        JsonNode items;
        try {
            items = objectMapper.readTree(json)
                    .path("data")
                    .path("products")
                    .path("items");
        } catch (Exception ex) {
            throw new RetailerException(
                    "Vijay Sales discovery response could not be parsed.", ex);
        }
        if (!items.isArray() || items.isEmpty()) {
            return List.of();
        }
        List<DiscoveredProduct> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : items) {
            if (out.size() >= limit) {
                break;
            }
            if (isWarrantyOrServiceItem(item)) {
                continue;
            }
            DiscoveredProduct candidate = toCandidate(item);
            if (candidate == null) {
                continue; // category/nav/external or identity-less entry — ignored
            }
            if (seen.add(DiscoveredProduct.dedupeKey(candidate.productUrl()))) {
                out.add(candidate);
            }
        }
        return List.copyOf(out);
    }

    private static boolean isWarrantyOrServiceItem(JsonNode item) {
        String title = item.path("name").asText("").toLowerCase(Locale.ROOT);
        if (title.isBlank()) {
            return false;
        }
        String[] serviceIndicators = {
                "extended warranty", "ew+", "warranty plan", "protection plan",
                "care plan", "service plan", "onsite go warranty", "store display unit",
                "vs+ ew", "1 yr", "1 year"
        };
        for (String indicator : serviceIndicators) {
            if (title.contains(indicator)) {
                return true;
            }
        }
        return false;
    }

    static int parseTotalCount(String json) {
        try {
            JsonNode totalNode = new ObjectMapper().readTree(json)
                    .path("data")
                    .path("products")
                    .path("total_count");
            if (totalNode.isInt()) {
                return totalNode.asInt();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return -1;
    }

    private DiscoveredProduct toCandidate(JsonNode item) {
        String url = resolveProductUrl(item.path("product_url").asText(""));
        if (!isTrackableProductUrl(url)) {
            return null;
        }
                String title = item.path("name").asText("").trim();
        if (title.isEmpty()) {
            return null; // nothing to display or identity-validate later
        }
        String model = extractModel(item, title);
        return new DiscoveredProduct(
                RetailerKey.VIJAY_SALES,
                url,
                title,
                item.path("sku").asText(""),
                model,
                isValidProductImage(item.path("image").path("url").asText(""))
                        ? item.path("image").path("url").asText("")
                        : "",
                item.path("mrp").asText(""),
                mapAvailability(item.path("stock_status").asText("")),
                item.path("price_range").path("maximum_price").path("final_price")
                        .path("value").asText(""),
                                item.path("price_range").path("maximum_price").path("final_price")
                        .path("currency").asText(""));
    }

    /**
     * Extracts the manufacturer model from Vijay Sales GraphQL item data and
     * from title-embedded model patterns (e.g. "LG AS-Q20ENZE"). Never
     * fabricates a value: returns "" when no model can be confidently identified.
     */
    static String extractModel(JsonNode item, String title) {
        // Try explicit model fields in the GraphQL response first.
        String model = item.path("model").asText("").trim();
        if (!model.isBlank() && model.length() >= 3 && model.length() <= 30) {
            return model;
        }
        String mpn = item.path("mpn").asText("").trim();
        if (!mpn.isBlank() && mpn.length() >= 3 && mpn.length() <= 30
                && mpn.matches("[A-Za-z0-9-_.]+")) {
            return mpn;
        }
        // Fallback: look for a model code in parentheses within the title
        // (e.g. "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)").
        if (!title.isBlank()) {
            java.util.regex.Matcher m = MODEL_IN_TITLE_PATTERN.matcher(title);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return "";
    }

    private static final java.util.regex.Pattern MODEL_IN_TITLE_PATTERN =
            java.util.regex.Pattern.compile("\\(([A-Z][A-Za-z0-9-]{2,19})\\)");

    private static boolean isValidProductImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return false;
        String lower = imageUrl.toLowerCase(Locale.ROOT);
        int queryIdx = lower.indexOf('?');
        String pathOnly = queryIdx >= 0 ? lower.substring(0, queryIdx) : lower;
        if (pathOnly.contains("logo") || pathOnly.contains("brand")
                || pathOnly.contains("icon") || pathOnly.contains("sprite")
                || pathOnly.contains("bg") || pathOnly.contains("placeholder")) {
            return false;
        }
        if (pathOnly.endsWith(".svg")) return false;
        if (pathOnly.endsWith(".jpg") || pathOnly.endsWith(".jpeg")
                || pathOnly.endsWith(".png") || pathOnly.endsWith(".webp")
                || pathOnly.endsWith(".gif")) return true;
        return pathOnly.contains(".img") || pathOnly.contains("/image")
                || pathOnly.contains("/img/");
    }

    private static String resolveProductUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.startsWith("/")) {
            url = SITE_ROOT + url;
        }
        return url;
    }

    /**
     * Product URLs only: http/https on the retailer host with a
     * /p/{sku}/{slug...} path. Excludes category (/c/...), search, event and
     * navigation pages, and refuses any foreign host (SSRF-safe). Public so
     * retailer-link validation (Phase 4) reuses the exact same rule set.
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
            if (!path.startsWith("/p/")) {
                return false;
            }
            return path.substring(3).split("/").length >= 2; // /p/{sku}/{slug...}
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Keeps the query safe to embed in a GraphQL string literal and stable
     * across calls: quotes, backslashes and control characters are dropped
     * and whitespace collapsed. No NLP, no stemming — plain sanitization.
     */
    static String normalizeQuery(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[\"\\\\\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String buildGraphqlQuery(String search, int pageSize, int currentPage) {
        return "{products(search:\"" + search + "\",pageSize:" + Math.max(1, pageSize)
                + ",currentPage:" + Math.max(1, currentPage)
                + "){total_count items{name sku product_url stock_status mrp image{url} "
                + "price_range{maximum_price{final_price{currency value}}}}}}";
    }

    static String mapAvailability(String stockStatus) {
        if (stockStatus == null || stockStatus.isBlank()) {
            return "";
        }
        return switch (stockStatus.trim().toUpperCase(Locale.ROOT)) {
            case "IN_STOCK" -> "In stock";
            case "OUT_OF_STOCK" -> "Out of stock";
            default -> "";
        };
    }

    private static String encodeQueryParam(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

