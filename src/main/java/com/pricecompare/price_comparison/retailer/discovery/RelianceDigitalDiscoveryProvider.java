package com.pricecompare.price_comparison.retailer.discovery;

import com.pricecompare.price_comparison.retailer.RetailerException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.retailer.RetailerPrice;
import com.pricecompare.price_comparison.service.ServiceExecutors;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovery provider for Reliance Digital.
 *
 * <p><b>Access basis (robots-compliant by construction):</b>
 * reliancedigital.in/robots.txt permits product pages (the default
 * {@code Disallow:} is empty) but disallows every query-string URL
 * ({@code Disallow: /*?*}), so the retailer's {@code ?searchQuery=...}
 * search endpoint is deliberately NOT used. Discovery instead walks the
 * product sitemap that robots.txt explicitly publishes for crawlers
 * ("Sitemap: https://www.reliancedigital.in/sitemap.xml", published under the
 * comment "Allow search crawlers to discover the sitemap"). Sitemap URLs are
 * plain {@code /product/...} pages without query strings.</p>
 *
 * <p>The robots-published product URL list is fetched once per JVM and cached
 * in memory (sequential, bounded requests). Candidates are slug-filtered,
 * de-duplicated, capped, and completed with the real public product-page data
 * (title, SKU, price) parsed from the static JSON-LD that the normal product
 * page returns. Those per-candidate page reads are independent blocking GETs,
 * so they run through a small fixed-size daemon pool
 * ({@link #FETCH_CONCURRENCY} at a time) whose results are consumed in
 * submission order &mdash; the candidate list is unchanged, only the number of
 * serial round-trips drops. No CAPTCHA/anti-bot/login bypass, no private
 * endpoints, no query-string crawling, and no fabricated data: a product the
 * retailer does not publish simply yields no candidate.</p>
 */
@Component
public class RelianceDigitalDiscoveryProvider implements ProductDiscoveryProvider {

    static final int DEFAULT_MAX_CANDIDATES = 5;

    private static final String SITEMAP_INDEX_URL = "https://www.reliancedigital.in/sitemap.xml";
    private static final String PRODUCT_HOST = "www.reliancedigital.in";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";

    private static final Pattern LOC_PATTERN =
            Pattern.compile("<loc>\\s*([^<\\s]+)\\s*</loc>");
    private static final Pattern JSON_LD_PATTERN =
            Pattern.compile("<script[^>]*type=.application/ld\\+json.[^>]*>([\\s\\S]*?)</script>",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern H1_PATTERN =
            Pattern.compile("<h1[^>]*>([\\s\\S]*?)</h1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern AVAILABILITY_PATTERN =
            Pattern.compile("\"availability\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TAGS_PATTERN = Pattern.compile("<[^>]+>");

    // JSON-LD Product "image" may be a string URL, an array of strings, or an
    // array of ImageObject nodes (each carrying a "url" field). This helper
    // extracts the first non-blank, non-logo-ish usable image URL from any of
    // those shapes. It never fabricates an image: if no usable image is found in
    // the page's JSON-LD, it returns the empty string and the card simply shows
    // no image (the frontend's existing onerror/missing-image behavior applies).
    static String firstImageUrlFromPage(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Matcher m = JSON_LD_PATTERN.matcher(html);
        while (m.find()) {
            String cdn = firstImageUrl(m.group(1));
            if (cdn != null) {
                return cdn;
            }
        }
        // Some callers hand this helper the JSON-LD document itself rather than
        // a full page, so fall back to reading the whole input as the JSON-LD
        // block. A real HTML page never reaches this path: its
        // <script type="application/ld+json"> blocks matched above, and if they
        // carry no usable image the readTree below simply fails on the markup
        // and the caller still gets "" (no image invented).
        String cdn = firstImageUrl(html);
        return cdn == null ? "" : cdn;
    }

    static String firstImageUrl(String jsonLdBlock) {
        if (jsonLdBlock == null || jsonLdBlock.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(jsonLdBlock);
            JsonNode image = root.path("image");
            if (image.isMissingNode() || image.isNull()) {
                return null;
            }
            if (image.isArray()) {
                for (JsonNode element : image) {
                    String url = imageNodeUrl(element);
                    if (url != null && !url.isBlank()) {
                        return nonEmptyUrl(url);
                    }
                }
                return null;
            }
            // Single value: a plain string URL or an ImageObject. Note that a
            // container node's asText() is always "", so there must be NO
            // "asText().isBlank()" guard before the array handling above --
            // adding one here previously rejected every array and every
            // ImageObject before their own branches could run. A blank scalar
            // is already rejected below, by imageNodeUrl itself.
            return imageNodeUrl(image);
        } catch (Exception ignored) {
            return null;
        }
    }

    static String imageNodeUrl(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        // ImageObject: {"@type":"ImageObject","url":"..."}
        if (node.has("url")) {
            String url = node.path("url").asText("");
            if (url != null && !url.isBlank()) {
                return nonEmptyUrl(url);
            }
        }
        // Plain string URL.
        String text = node.asText("");
        if (text != null && !text.isBlank()) {
            return nonEmptyUrl(text);
        }
        return null;
    }

    // Accepts a bare image URL from JSON-LD and rejects obvious placeholder/
    // logo/logo-image values that Shopify/JSON-LD feeds sometimes carry. Keeps
    // every genuine-looking URL through; never invents one.
    static String nonEmptyUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.contains("logo") || lower.contains("brand")
                || lower.contains("icon") || lower.contains("sprite")
                || lower.contains("placeholder") || lower.contains("banner")
                || lower.contains("background") || lower.contains("bg-")
                || lower.contains("default-image") || lower.contains("no-image")) {
            return null;
        }
        return cleaned;
    }

        /**
     * Extracts the manufacturer model from JSON-LD, falling back to title-embedded
     * model patterns (e.g. "AS-Q20ENZE", "CPH2467"). Never fabricates a value:
     * returns "" when no model can be confidently identified.
     */
    static String extractModel(JsonNode node, String title, String h1) {
        String model = node.path("model").asText("").trim();
        if (!model.isBlank()) {
            return model;
        }
        String mpn = node.path("mpn").asText("").trim();
        if (!mpn.isBlank() && mpn.length() >= 3 && mpn.length() <= 30
                && mpn.matches("[A-Za-z0-9-_.]+")) {
            return mpn;
        }
        // Fallback: look for a model code in parentheses within the title or h1
        // (e.g. "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)").
        String source = title.isBlank() ? (h1 == null ? "" : h1) : title;
        if (!source.isBlank()) {
            java.util.regex.Matcher m = MODEL_IN_TITLE_PATTERN.matcher(source);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return "";
    }

    private static final java.util.regex.Pattern MODEL_IN_TITLE_PATTERN =
            java.util.regex.Pattern.compile("\\(([A-Z][A-Za-z0-9-]{2,19})\\)");

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    /**
     * How many product pages may be requested at once while completing the
     * slug-matched candidates. These are independent, public GETs of plain
     * {@code /product/...} URLs, so a small fixed window is both safe and
     * polite; the bound keeps the number of concurrent requests independent of
     * the size of the robots-published sitemap.
     */
    private static final int FETCH_CONCURRENCY = 8;

    private final RestClient restClient;
    private final int maxCandidates;
    private final ExecutorService pageExecutor;
    private volatile Set<String> productUrlCache;

    public RelianceDigitalDiscoveryProvider(
            @Value("${app.discovery.reliancedigital.max-candidates:5}") int maxCandidates) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        this.maxCandidates = Math.max(1, maxCandidates);
        this.pageExecutor = ServiceExecutors.newBoundedExecutor(
                "reliancedigital-discovery", FETCH_CONCURRENCY);
    }

    @PreDestroy
    public void shutdownExecutor() {
        pageExecutor.shutdown();
    }

    @Override
    public RetailerKey retailer() {
        return RetailerKey.RELIANCE_DIGITAL;
    }

    @Override
    public List<DiscoveredProduct> discover(String query) {
        Set<String> queryChunks = slugChunks(query);
        if (queryChunks.isEmpty()) {
            return List.of();
        }

        // Slug-filter the robots-published list FIRST: only URLs that can
        // actually produce a candidate are ever requested, so a search never
        // walks the whole sitemap.
        List<String> matches = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String url : productUrls()) {
            if (!seen.add(DiscoveredProduct.dedupeKey(url))) {
                continue;
            }
            if (slugChunks(url).containsAll(queryChunks)) {
                matches.add(url);
            }
        }

        // Completing a candidate needs one blocking fetch of its public product
        // page. Doing that strictly one URL at a time was the dominant cost of a
        // Reliance Digital search (a hundred candidates cost a hundred serial
        // round-trips), so the matched pages are now fetched in bounded
        // concurrent windows instead. The window width is the pool's fixed size
        // and futures are consumed in submission order, so the returned list is
        // exactly the list the previous sequential loop produced: same order,
        // same cap, same "a failed page skips only itself" behaviour.
        List<DiscoveredProduct> candidates = new ArrayList<>();
        for (int start = 0;
                start < matches.size() && candidates.size() < maxCandidates;
                start += FETCH_CONCURRENCY) {
            int needed = maxCandidates - candidates.size();
            int end = Math.min(matches.size(), start + Math.min(FETCH_CONCURRENCY, needed));
            List<CompletableFuture<DiscoveredProduct>> pages = new ArrayList<>(end - start);
            for (int index = start; index < end; index++) {
                String url = matches.get(index);
                pages.add(CompletableFuture.supplyAsync(() -> fetchCandidate(url), pageExecutor));
            }
            for (CompletableFuture<DiscoveredProduct> page : pages) {
                DiscoveredProduct candidate = page.join();
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    /**
     * Fetches and parses exactly one public product page. Returns {@code null}
     * when that page cannot be used (request failure, or no usable published
     * price), so a single bad page skips only itself and never fails the whole
     * discovery.
     */
    private DiscoveredProduct fetchCandidate(String url) {
        try {
            String html = fetch(url);
            return parseProductPage(html, url).stream()
                    .findFirst()
                    .map(price -> toCandidate(price, availabilityOf(html),
                            firstImageUrlFromPage(html)))
                    .orElse(null);
        } catch (Exception ignored) {
            return null; // skip this candidate only
        }
    }

        private DiscoveredProduct toCandidate(RetailerPrice price, String availability, String imageUrl) {
        return new DiscoveredProduct(
                RetailerKey.RELIANCE_DIGITAL,
                price.productUrl(),
                price.productTitle(),
                price.sku(),
                price.model(),
                imageUrl,
                "",
                availability,
                price.price() == null ? "" : price.price().toPlainString(),
                "INR");
    }

    /**
     * Parses the static public product page into the normalized price record.
     * Visible/public data only: the page's JSON-LD Product/Offer block plus the
     * rendered H1. No usable price raises a controlled {@link RetailerException}
     * â€” never a zero, null, or fabricated value.
     */
    public static List<RetailerPrice> parseProductPage(String html, String requestedUrl) {
        if (html == null || html.isBlank()) {
            throw new RetailerException("Reliance Digital returned no content for " + requestedUrl);
        }
        String h1 = extractH1(html);
        for (String block : jsonLdBlocks(html)) {
            JsonNode node;
            try {
                node = OBJECT_MAPPER.readTree(block);
            } catch (Exception ignored) {
                continue; // not JSON; try the next block
            }
            JsonNode offers = node.path("offers");
            if (offers.isArray() && offers.size() > 0) {
                offers = offers.get(0);
            }
            JsonNode priceNode = offers.path("price");
            if (priceNode.isMissingNode() || priceNode.isNull()) {
                continue;
            }
            BigDecimal price = parseAmount(priceNode.asText(""));
            if (price == null) {
                continue;
            }
                         String title = node.path("name").asText("").trim();
            if (title.isBlank()) {
                title = h1;
            }
            return List.of(new RetailerPrice(
                    RetailerKey.RELIANCE_DIGITAL,
                    price,
                    canonicalUrl(node, requestedUrl),
                    title,
                    LocalDateTime.now(),
                    skuFromUrl(requestedUrl),
                    extractModel(node, title, h1)));
        }
        throw new RetailerException("Reliance Digital price unavailable: no parsable offer/price "
                + "found on the public product page " + requestedUrl);
    }

    /**
     * Product URLs only: http/https on the Reliance Digital host with a
     * /product/... path and NO query string (robots.txt disallows
     * {@code /*?*}, so our own fetches never use one). Refuses foreign hosts
     * (SSRF-safe), non-product paths, and malformed URLs. Public so the price
     * provider reuses the exact same rule set.
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
            if (host == null || !(PRODUCT_HOST.equalsIgnoreCase(host)
                    || "reliancedigital.in".equalsIgnoreCase(host))) {
                return false;
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (!path.startsWith("/product/")) {
                return false;
            }
            String query = uri.getRawQuery();
            return query == null || query.isBlank();
        } catch (Exception ex) {
            return false;
        }
    }

    /** Hyphen/word chunks used for slug matching (URL slug vs query). */
    static Set<String> slugChunks(String value) {
        Set<String> out = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String chunk : value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!chunk.isEmpty()) {
                out.add(chunk);
            }
        }
        return out;
    }

    /** Maps the JSON-LD availability IRI to a short display value. */
    static String availabilityOf(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Matcher matcher = AVAILABILITY_PATTERN.matcher(html);
        if (matcher.find()) {
            String value = matcher.group(1).toLowerCase(Locale.ROOT);
            if (value.endsWith("instock")) {
                return "In stock";
            }
            if (value.endsWith("outofstock")) {
                return "Out of stock";
            }
        }
        return "";
    }

    /**
     * The retailer product id is the trailing numeric chunk of the public
     * product URL (e.g. ...-as-q20enze-10056092 -> 10056092). Empty when the
     * URL carries no numeric id.
     */
    static String skuFromUrl(String url) {
        try {
            String path = URI.create(url.trim()).getPath();
            if (path == null) {
                return "";
            }
            String[] segments = path.split("/");
            String[] chunks = segments[segments.length - 1].split("-");
            String last = chunks[chunks.length - 1];
            return last.matches("\\d{4,}") ? last : "";
        } catch (Exception ex) {
            return "";
        }
    }

    /** Controlled amount parsing: null/blank/"null"/malformed -> null. */
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

    static List<String> extractLocs(String xml) {
        List<String> locs = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return locs;
        }
        Matcher matcher = LOC_PATTERN.matcher(xml);
        while (matcher.find()) {
            locs.add(matcher.group(1).trim());
        }
        return locs;
    }

    static List<String> jsonLdBlocks(String html) {
        List<String> blocks = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return blocks;
        }
        Matcher matcher = JSON_LD_PATTERN.matcher(html);
        while (matcher.find()) {
            blocks.add(matcher.group(1));
        }
        return blocks;
    }

    private static String extractH1(String html) {
        Matcher matcher = H1_PATTERN.matcher(html);
        if (matcher.find()) {
            return TAGS_PATTERN.matcher(matcher.group(1)).replaceAll("").trim();
        }
        return "";
    }

    private static String canonicalUrl(JsonNode node, String requestedUrl) {
        String url = node.path("url").asText("").trim();
        return isTrackableProductUrl(url) ? url : requestedUrl;
    }

    /** Robots-published product URLs, loaded once per JVM and cached. */
    private Set<String> productUrls() {
        Set<String> cache = productUrlCache;
        if (cache == null) {
            synchronized (this) {
                if (productUrlCache == null) {
                    productUrlCache = loadProductUrls();
                }
                cache = productUrlCache;
            }
        }
        return cache;
    }

    private Set<String> loadProductUrls() {
        Set<String> productSitemaps = new LinkedHashSet<>();
        for (String loc : extractLocs(fetch(SITEMAP_INDEX_URL))) {
            if (loc.contains("/sitemap/products/")) {
                productSitemaps.add(loc);
            }
        }
        if (productSitemaps.isEmpty()) {
            throw new RetailerException(
                    "Reliance Digital sitemap index publishes no product sitemaps.");
        }
        Set<String> urls = new LinkedHashSet<>();
        for (String sitemap : productSitemaps) {
            for (String loc : extractLocs(fetch(sitemap))) {
                if (isTrackableProductUrl(loc)) {
                    urls.add(loc);
                }
            }
        }
        if (urls.isEmpty()) {
            throw new RetailerException(
                    "Reliance Digital product sitemaps published no usable product URLs.");
        }
        return urls;
    }

    private String fetch(String url) {
        try {
            String body = restClient.get()
                    .uri(URI.create(url))
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .header(HttpHeaders.ACCEPT,
                            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                throw new RetailerException("Reliance Digital returned no content for " + url);
            }
            return body;
        } catch (RetailerException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RetailerException("Reliance Digital request failed for " + url, ex);
        }
    }
}