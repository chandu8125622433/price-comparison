package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.dto.CategoryListingResponse;
import com.pricecompare.price_comparison.dto.CategoryListingResponse.CategoryProductCard;
import com.pricecompare.price_comparison.dto.CategoryListingResponse.FilterGroup;
import com.pricecompare.price_comparison.dto.CategoryListingResponse.FilterOption;
import com.pricecompare.price_comparison.entity.RetailerLink;
import com.pricecompare.price_comparison.repository.RetailerLinkRepository;
import com.pricecompare.price_comparison.retailer.discovery.DiscoveredProduct;
import com.pricecompare.price_comparison.retailer.discovery.ProductDiscoveryProvider;
import com.pricecompare.price_comparison.retailer.discovery.ProductDiscoveryRegistry;
import com.pricecompare.price_comparison.service.ProductSpec;
import com.pricecompare.price_comparison.service.ProductSpecExtractor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * Orchestrates CATEGORY search: discovers retailer product candidates for a
 * canonical category query, filters by relevance, de-duplicates across
 * retailers, and assembles a product-listing page.
 */
@Component
public class CategoryDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryDiscoveryService.class);

    private final ProductDiscoveryRegistry discoveryRegistry;
    private final SearchClassifier classifier;
    private final CategoryRelevanceFilter relevanceFilter;
    private final RetailerLinkRepository retailerLinkRepository;
    private final ExecutorService executor;
    private static final Map<SearchClassifier.Category, String> CATEGORY_DISPLAY = Map.of(
            SearchClassifier.Category.MOBILE, "Mobiles",
            SearchClassifier.Category.LAPTOP, "Laptops",
            SearchClassifier.Category.TV, "Televisions",
            SearchClassifier.Category.AC, "Air Conditioners",
            SearchClassifier.Category.REFRIGERATOR, "Refrigerators",
            SearchClassifier.Category.WASHING_MACHINE, "Washing Machines",
            SearchClassifier.Category.HEADPHONES, "Headphones",
            SearchClassifier.Category.TABLET, "Tablets",
            SearchClassifier.Category.CAMERA, "Cameras",
            SearchClassifier.Category.SMARTWATCH, "Smartwatches"
    );

    private static final Map<SearchClassifier.Category, String> CATEGORY_DESC = Map.of(
            SearchClassifier.Category.MOBILE, "Find and compare mobile phones from available retailers.",
            SearchClassifier.Category.LAPTOP, "Find and compare laptops from available retailers.",
            SearchClassifier.Category.TV, "Find and compare televisions from available retailers.",
            SearchClassifier.Category.AC, "Find and compare air conditioners from available retailers.",
            SearchClassifier.Category.REFRIGERATOR, "Find and compare refrigerators from available retailers.",
            SearchClassifier.Category.WASHING_MACHINE, "Find and compare washing machines from available retailers.",
            SearchClassifier.Category.HEADPHONES, "Find and compare headphones and earphones from available retailers.",
            SearchClassifier.Category.TABLET, "Find and compare tablets from available retailers.",
            SearchClassifier.Category.CAMERA, "Find and compare cameras from available retailers.",
            SearchClassifier.Category.SMARTWATCH, "Find and compare smartwatches from available retailers."
    );

    private static final Map<SearchClassifier.Category, String> CATEGORY_DISCOVERY_QUERY = Map.of(
            SearchClassifier.Category.MOBILE, "smartphone",
            SearchClassifier.Category.LAPTOP, "laptop",
            SearchClassifier.Category.TV, "tv",
            SearchClassifier.Category.AC, "air conditioner",
            SearchClassifier.Category.REFRIGERATOR, "refrigerator",
            SearchClassifier.Category.WASHING_MACHINE, "washing machine",
            SearchClassifier.Category.HEADPHONES, "headphones",
            SearchClassifier.Category.TABLET, "tablet",
            SearchClassifier.Category.CAMERA, "camera",
            SearchClassifier.Category.SMARTWATCH, "smartwatch"
    );

    public CategoryDiscoveryService(ProductDiscoveryRegistry discoveryRegistry,
                                    SearchClassifier classifier,
                                    CategoryRelevanceFilter relevanceFilter,
                                    RetailerLinkRepository retailerLinkRepository) {
        this.discoveryRegistry = discoveryRegistry;
        this.classifier = classifier;
        this.relevanceFilter = relevanceFilter;
        this.retailerLinkRepository = retailerLinkRepository;
        this.executor = ServiceExecutors.newBoundedExecutor("category-discovery");
    }

    @PreDestroy
    public void shutdownExecutor() {
        executor.shutdown();
    }

    public CategoryListingResponse discover(SearchClassifier.Category category, String rawQuery) {
        return discover(category, rawQuery, 1, 100);
    }

    public CategoryListingResponse discover(SearchClassifier.Category category, String rawQuery, int page, int pageSize) {
        return discover(category, rawQuery, page, pageSize, List.of(), null, null, "price-asc", Map.of());
    }

    /**
     * Builds a page from the normalized, actually discovered catalogue.  Filters
     * are intentionally applied before pagination, so a result count and a
     * "Load more" request describe the same set of products.
     *
     * @param specFilters spec name→value pairs to filter on (e.g. "ram"→"8");
     *                    empty or null means no spec filtering.
     */
    public CategoryListingResponse discover(SearchClassifier.Category category, String rawQuery, int page, int pageSize,
                                            List<String> brands, BigDecimal minPrice, BigDecimal maxPrice, String sort,
                                            Map<String, List<String>> specFilters) {
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 100;
        if (pageSize > 100) pageSize = 100;

        String discoveryQuery = resolveDiscoveryQuery(category, rawQuery);
        log.info("[category-discovery] category={}, query='{}', discoveryQuery='{}', page={}, pageSize={}",
                category, rawQuery, discoveryQuery, page, pageSize);
        List<DiscoveredProduct> allCandidates = collectCandidates(discoveryQuery);

        List<DiscoveredProduct> relevant = new ArrayList<>();
        for (DiscoveredProduct candidate : allCandidates) {
            if (relevanceFilter.isRelevant(category, candidate.productTitle(),
                    candidate.sourceCategory())) {
                relevant.add(candidate);
            }
        }

Map<String, List<DiscoveredProduct>> grouped = groupByProduct(relevant);

        // Collect all product URLs to batch fetch retailer links
        Set<String> allProductUrls = new HashSet<>();
        for (List<DiscoveredProduct> group : grouped.values()) {
            for (DiscoveredProduct candidate : group) {
                if (candidate.productUrl() != null) {
                    allProductUrls.add(candidate.productUrl());
                }
            }
        }
        
        // Batch fetch retailer links
        Map<String, Long> productUrlToId = new HashMap<>();
        if (!allProductUrls.isEmpty()) {
            List<RetailerLink> links = retailerLinkRepository.findByProductUrlIn(allProductUrls);
            for (RetailerLink link : links) {
                productUrlToId.put(link.getProductUrl(), link.getProduct().getId());
            }
        }

        List<CategoryProductCard> allCards = new ArrayList<>();
        for (Map.Entry<String, List<DiscoveredProduct>> entry : grouped.entrySet()) {
            List<DiscoveredProduct> group = entry.getValue();
            if (group.isEmpty()) continue;
            allCards.add(buildProductCard(category, group, productUrlToId));
        }

        List<FilterGroup> filters = buildFilters(allCards);
        filters.addAll(buildSpecFilterGroups(category, allCards));
        List<CategoryProductCard> filteredCards = filterCards(allCards, brands, minPrice, maxPrice, specFilters);
        sortCards(filteredCards, sort);
        int totalProducts = filteredCards.size();

        // Paginate
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, filteredCards.size());
        List<CategoryProductCard> pageCards = (start >= filteredCards.size()) ? List.of() : filteredCards.subList(start, end);
        String display = CATEGORY_DISPLAY.getOrDefault(category, capitalize(category.name()));
        String desc = CATEGORY_DESC.getOrDefault(category, "");

        return new CategoryListingResponse(
                rawQuery,
                category.name().toLowerCase(),
                display,
                desc,
                totalProducts,
                pageCards,
                filters
        );
    }

    private String resolveDiscoveryQuery(SearchClassifier.Category category, String rawQuery) {
        if (rawQuery != null && !rawQuery.isBlank()) {
            String normalized = rawQuery.trim().toLowerCase(Locale.ROOT);
            for (String alias : classifier.categoryAliases().keySet()) {
                if (normalized.endsWith(" " + alias)) {
                    String brand = normalized.substring(0, normalized.length() - alias.length()).trim();
                    if (!brand.isEmpty()) {
                        return brand + " " + CATEGORY_DISCOVERY_QUERY.getOrDefault(category, alias);
                    }
                }
            }
        }
        return CATEGORY_DISCOVERY_QUERY.getOrDefault(category, rawQuery);
    }

    private List<DiscoveredProduct> collectCandidates(String discoveryQuery) {
        List<ProductDiscoveryProvider> providers = discoveryRegistry.allProviders();
        List<DiscoveredProduct> all = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();

        // Run the independent retailer discovery providers concurrently on the
        // bounded executor. Outcomes are merged below in registry order, so
        // ordering, first-wins URL de-duplication and full source consumption
        // are identical to the sequential run.
        List<CompletableFuture<CandidateOutcome>> futures = new ArrayList<>();
        for (ProductDiscoveryProvider provider : providers) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    List<DiscoveredProduct> found = provider.discover(discoveryQuery);
                    log.info("[category-discovery] {}: {} candidate(s) for '{}'",
                            provider.retailer().getDisplayName(), found.size(), discoveryQuery);
                    return new CandidateOutcome(found);
                } catch (Exception ex) {
                    log.warn("[category-discovery] {} failed for '{}': {}",
                            provider.retailer().getDisplayName(), discoveryQuery, ex.getMessage());
                    return new CandidateOutcome(ex);
                }
            }, executor));
        }
        for (int i = 0; i < providers.size(); i++) {
            CandidateOutcome outcome = futures.get(i).join();
            if (outcome.failed()) {
                continue;
            }
            for (DiscoveredProduct candidate : outcome.products()) {
                String key = DiscoveredProduct.dedupeKey(candidate.productUrl());
                if (seenUrls.add(key)) {
                    all.add(candidate);
                }
            }
        }
        return all;
    }

    private Map<String, List<DiscoveredProduct>> groupByProduct(List<DiscoveredProduct> candidates) {
        Map<String, List<DiscoveredProduct>> grouped = new LinkedHashMap<>();
        for (DiscoveredProduct candidate : candidates) {
            String key = normalizeIdentity(candidate);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(candidate);
        }
        return grouped;
    }

    private String normalizeIdentity(DiscoveredProduct candidate) {
        String title = candidate.productTitle() == null ? "" : candidate.productTitle();
        String lower = title.toLowerCase(Locale.ROOT);
        lower = lower.replaceAll("[^a-z0-9\\s]", " ");
        lower = lower.replaceAll("\\s+", " ").trim();
        lower = lower.replaceAll("\\s+(white|black|blue|silver|gold|grey|gray|green|red|pink|violet)\\s*$", "");
        return lower;
    }

private CategoryProductCard buildProductCard(SearchClassifier.Category category,
                                                  List<DiscoveredProduct> group,
                                                  Map<String, Long> productUrlToId) {
        DiscoveredProduct first = group.get(0);
        String name = first.productTitle();
        String brand = extractBrand(name);
        List<String> retailerNames = new ArrayList<>();
        Set<String> retailerSet = new HashSet<>();
        BigDecimal lowest = null;
        Long productId = null;
        String productUrl = first.productUrl();
        
        for (DiscoveredProduct candidate : group) {
            String retailerName = candidate.retailer().getDisplayName();
            if (retailerSet.add(retailerName)) retailerNames.add(retailerName);
            BigDecimal price = parsePrice(candidate.price());
            if (price != null && (lowest == null || price.compareTo(lowest) < 0)) lowest = price;
            
            // Try to find existing database product via pre-fetched map
            if (productId == null && candidate.productUrl() != null) {
                Long id = productUrlToId.get(candidate.productUrl());
                if (id != null) {
                    productId = id;
                }
            }
        }
        String lowestPrice = lowest == null ? null : formatPrice(lowest);
        int offerCount = retailerNames.size();
        String availability = determineAvailability(group);
        ProductSpec specs = ProductSpecExtractor.fromTitle(name, category);
        List<CategoryListingResponse.CardOffer> offers = buildCardOffers(group);
        return new CategoryProductCard(
                productId, name, brand, first.imageUrl(),
                lowestPrice, null, offerCount, availability,
                productUrl, retailerNames, offerCount > 0, specs, offers
        );
    }

    /**
     * Builds the per-retailer offers for a product card from the candidate group.
     * Each registered retailer contributes at most one offer — the candidate with
     * the lowest parsed price for that retailer. Offers are never fabricated; a
     * retailer that did not expose a usable price contributes nothing.
     */
    private List<CategoryListingResponse.CardOffer> buildCardOffers(List<DiscoveredProduct> group) {
        Map<String, CategoryListingResponse.CardOffer> bestByRetailer = new LinkedHashMap<>();
        for (DiscoveredProduct candidate : group) {
            String retailer = candidate.retailer().getDisplayName();
            BigDecimal price = parsePrice(candidate.price());
            if (price == null) {
                continue; // retailer did not expose a usable price
            }
            CategoryListingResponse.CardOffer existing = bestByRetailer.get(retailer);
            // existing.price() is a DISPLAY string (formatPrice -> "₹45,490"),
            // so it has to be re-read through parsePrice. Calling new BigDecimal()
            // on it threw NumberFormatException on the currency symbol and failed
            // the entire category search with a 400 as soon as a card had a second
            // candidate from the same retailer.
            BigDecimal existingPrice = existing == null ? null : parsePrice(existing.price());
            if (existingPrice == null || price.compareTo(existingPrice) < 0) {
                bestByRetailer.put(retailer, new CategoryListingResponse.CardOffer(
                        retailer,
                        formatPrice(price),
                        candidate.availability(),
                        candidate.productUrl(),
                        candidate.mrp()
                ));
            }
        }
        return new ArrayList<>(bestByRetailer.values());
    }

    private String extractBrand(String title) {
        if (title == null || title.isEmpty()) return "";
        String lower = title.toLowerCase(Locale.ROOT);
        String[] brands = {"samsung", "apple", "xiaomi", "redmi", "oneplus",
                "oppo", "vivo", "realme", "nokia", "nothing", "poco", "motorola",
                "asus", "honor", "google", "sony", "lg", "lenovo", "hp", "dell",
                "acer", "mi", "iqoo", "infinix", "itel", "micromax", "boat",
                "jbl", "bose", "philips", "sennheiser", "skullcandy", "jabra"};
        for (String b : brands) {
            if (lower.matches("\\b" + b + "\\b.*") || lower.contains(" " + b + " ")) {
                return capitalize(b);
            }
        }
        String[] parts = title.trim().split("\\s+");
        return parts.length > 0 ? capitalize(parts[0]) : "";
    }

    private String determineAvailability(List<DiscoveredProduct> group) {
        for (DiscoveredProduct c : group) {
            String a = c.availability();
            if (a != null && !a.isEmpty()) {
                if (a.toLowerCase(Locale.ROOT).contains("in stock")) return "In stock";
            }
        }
        return "Check retailers";
    }

    private BigDecimal parsePrice(String price) {
        if (price == null || price.isEmpty()) return null;
        try {
            String cleaned = price.replaceAll("[^0-9.]", "").trim();
            if (cleaned.isEmpty()) return null;
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) return null;
        return "\u20b9" + String.format("%,.0f", price);
    }

    private List<CategoryProductCard> filterCards(List<CategoryProductCard> cards, List<String> brands,
                                                   BigDecimal minPrice, BigDecimal maxPrice, Map<String, List<String>> specFilters) {
        Set<String> selectedBrands = brands == null ? Set.of() : brands.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        Map<String, List<String>> activeSpecFilters = specFilters == null ? Map.of() : specFilters.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stream()
                        .filter(v -> v != null && !v.isBlank())
                        .map(String::trim)
                        .collect(Collectors.toList()),
                        (a, b) -> b, LinkedHashMap::new));
        return cards.stream().filter(card -> selectedBrands.isEmpty()
                        || selectedBrands.contains(String.valueOf(card.brand()).toLowerCase(Locale.ROOT)))
                .filter(card -> withinPrice(card, minPrice, maxPrice))
                .filter(card -> matchesSpecFilters(card, activeSpecFilters))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private boolean withinPrice(CategoryProductCard card, BigDecimal minPrice, BigDecimal maxPrice) {
        if (minPrice == null && maxPrice == null) return true;
        BigDecimal price = priceOf(card);
        return price != null && (minPrice == null || price.compareTo(minPrice) >= 0)
                && (maxPrice == null || price.compareTo(maxPrice) <= 0);
    }

    private void sortCards(List<CategoryProductCard> cards, String sort) {
        Comparator<CategoryProductCard> byPrice = Comparator.comparing(this::priceOf,
                Comparator.nullsLast(Comparator.naturalOrder()));
        if ("price-desc".equalsIgnoreCase(sort)) {
            byPrice = Comparator.comparing(this::priceOf,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        } else if ("name-asc".equalsIgnoreCase(sort)) {
            cards.sort(Comparator.comparing(CategoryProductCard::name, String.CASE_INSENSITIVE_ORDER));
            return;
        }
        cards.sort(byPrice.thenComparing(CategoryProductCard::name, String.CASE_INSENSITIVE_ORDER));
    }

    private BigDecimal priceOf(CategoryProductCard card) {
        if (card.lowestPrice() == null) return null;
        try {
            String cleaned = card.lowestPrice().replaceAll("[^0-9.]", "").trim();
            if (cleaned.isEmpty()) return null;
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<FilterGroup> buildFilters(List<CategoryProductCard> cards) {
        List<FilterGroup> filters = new ArrayList<>();
        Map<String, Integer> brandCount = new LinkedHashMap<>();
        for (CategoryProductCard card : cards) {
            String brand = card.brand() == null ? "Unknown" : card.brand();
            brandCount.merge(brand, 1, Integer::sum);
        }
        List<FilterOption> brandOptions = brandCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> new FilterOption(e.getKey(), e.getKey(), e.getValue()))
                .collect(Collectors.toList());
        filters.add(new FilterGroup("Brand", "brand", !brandOptions.isEmpty(), brandOptions));
        return filters;
    }

    private List<FilterOption> buildBudgetFilters(List<CategoryProductCard> cards) {
        int[][] buckets = {{0, 10000}, {10000, 20000}, {20000, 30000},
                {30000, 50000}, {50000, 100000}, {100000, Integer.MAX_VALUE}};
        String[] labels = {"Under \u20b910,000", "\u20b910,000-\u20b920,000", "\u20b920,000-\u20b930,000",
                "\u20b930,000-\u20b950,000", "\u20b950,000-\u20b91,00,000", "Above \u20b91,00,000"};
        int[] counts = new int[buckets.length];
        for (CategoryProductCard card : cards) {
            BigDecimal p = parsePrice(card.lowestPrice());
            if (p == null) continue;
            int v = p.intValue();
            for (int i = 0; i < buckets.length; i++) {
                if (v >= buckets[i][0] && v < buckets[i][1]) { counts[i]++; break; }
            }
        }
        List<FilterOption> options = new ArrayList<>();
        for (int i = 0; i < buckets.length; i++) {
            if (counts[i] > 0) options.add(new FilterOption(labels[i], labels[i], counts[i]));
        }
        return options;
    }

    private List<FilterGroup> buildSpecFilterGroups(SearchClassifier.Category category, List<CategoryProductCard> cards) {
        List<FilterGroup> groups = new ArrayList<>();
        if (cards.isEmpty()) return groups;
        for (String specKey : specKeysForCategory(category)) {
            Map<String, Integer> valueCounts = new LinkedHashMap<>();
            for (CategoryProductCard card : cards) {
                Optional<String> value = specValue(card.specs(), specKey);
                value.ifPresent(v -> valueCounts.merge(v, 1, Integer::sum));
            }
            if (valueCounts.size() < 2) continue;
            List<FilterOption> options = valueCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .map(e -> new FilterOption(specLabel(specKey, e.getKey()), e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
            groups.add(new FilterGroup(specDisplayName(specKey), specKey, !options.isEmpty(), options));
        }
        return groups;
    }

    private List<String> specKeysForCategory(SearchClassifier.Category category) {
        return switch (category) {
            case MOBILE -> List.of("ram", "storage", "processor", "fiveG");
            case LAPTOP -> List.of("ram", "storage", "processor", "screenSize");
            case TABLET -> List.of("ram", "storage", "processor", "screenSize");
            case TV -> List.of("screenSize", "resolution", "displayType", "refreshRate");
            case AC -> List.of("tonnage", "starRating", "acType");
            case REFRIGERATOR -> List.of("capacity", "doorType");
            case WASHING_MACHINE -> List.of("capacity", "loadType");
            case CAMERA -> List.of("cameraType", "resolutionMP");
            case HEADPHONES -> List.of("headphoneType", "wireless", "anc");
            case SMARTWATCH -> List.of("watchDisplay", "gps", "cellular");
            default -> List.of();
        };
    }

    private Optional<String> specValue(ProductSpec spec, String key) {
        return switch (key) {
            case "ram" -> spec.ram().map(Object::toString);
            case "storage" -> spec.storage().map(Object::toString);
            case "processor" -> spec.processor();
            case "screenSize" -> spec.screenSize().map(Object::toString);
            case "resolution" -> spec.resolution();
            case "displayType" -> spec.displayType();
            case "refreshRate" -> spec.refreshRate().map(Object::toString);
            case "tonnage" -> spec.tonnage().map(Object::toString);
            case "starRating" -> spec.starRating().map(Object::toString);
            case "inverter" -> spec.inverter().map(Object::toString);
            case "acType" -> spec.acType();
            case "capacity" -> spec.capacity().map(Object::toString);
            case "doorType" -> spec.doorType();
            case "loadType" -> spec.loadType();
            case "cameraType" -> spec.cameraType();
            case "resolutionMP" -> spec.resolutionMP().map(Object::toString);
            case "headphoneType" -> spec.headphoneType();
            case "wireless" -> spec.wireless().map(Object::toString);
            case "anc" -> spec.anc().map(Object::toString);
            case "watchDisplay" -> spec.watchDisplay();
            case "gps" -> spec.gps().map(Object::toString);
            case "cellular" -> spec.cellular().map(Object::toString);
            case "fiveG" -> spec.fiveG().map(Object::toString);
            default -> Optional.empty();
        };
    }

    private boolean matchesSpecFilter(CategoryProductCard card, String key, String value) {
        if (value == null || value.isBlank()) return true;
        ProductSpec spec = card.specs();
        if (spec == null) return false;
        return switch (key) {
            case "ram" -> spec.ram().isPresent() && spec.ram().get().toString().equals(value);
            case "storage" -> spec.storage().isPresent() && spec.storage().get().toString().equals(value);
            case "processor" -> spec.processor().isPresent()
                    && spec.processor().get().equalsIgnoreCase(value);
            case "screenSize" -> spec.screenSize().isPresent()
                    && spec.screenSize().get().toString().equals(value);
            case "resolution" -> spec.resolution().isPresent()
                    && spec.resolution().get().equalsIgnoreCase(value);
            case "displayType" -> spec.displayType().isPresent()
                    && spec.displayType().get().equalsIgnoreCase(value);
            case "refreshRate" -> spec.refreshRate().isPresent()
                    && spec.refreshRate().get().toString().equals(value);
            case "tonnage" -> spec.tonnage().isPresent()
                    && spec.tonnage().get().toString().equals(value);
            case "starRating" -> spec.starRating().isPresent()
                    && spec.starRating().get().toString().equals(value);
            case "inverter" -> spec.inverter().isPresent()
                    && spec.inverter().get().toString().equals(value);
            case "acType" -> spec.acType().isPresent()
                    && spec.acType().get().equalsIgnoreCase(value);
            case "capacity" -> spec.capacity().isPresent()
                    && spec.capacity().get().toString().equals(value);
            case "doorType" -> spec.doorType().isPresent()
                    && spec.doorType().get().equalsIgnoreCase(value);
            case "loadType" -> spec.loadType().isPresent()
                    && spec.loadType().get().equalsIgnoreCase(value);
            case "cameraType" -> spec.cameraType().isPresent()
                    && spec.cameraType().get().equalsIgnoreCase(value);
            case "resolutionMP" -> spec.resolutionMP().isPresent()
                    && spec.resolutionMP().get().toString().equals(value);
            case "headphoneType" -> spec.headphoneType().isPresent()
                    && spec.headphoneType().get().equalsIgnoreCase(value);
            case "wireless" -> spec.wireless().isPresent()
                    && spec.wireless().get().toString().equals(value);
            case "anc" -> spec.anc().isPresent()
                    && spec.anc().get().toString().equals(value);
            case "watchDisplay" -> spec.watchDisplay().isPresent()
                    && spec.watchDisplay().get().equalsIgnoreCase(value);
            case "gps" -> spec.gps().isPresent()
                    && spec.gps().get().toString().equals(value);
            case "cellular" -> spec.cellular().isPresent()
                    && spec.cellular().get().toString().equals(value);
            case "fiveG" -> spec.fiveG().isPresent()
                    && spec.fiveG().get().toString().equals(value);
            default -> true;
        };
    }

    private boolean matchesSpecFilters(CategoryProductCard card, Map<String, List<String>> specFilters) {
        for (Map.Entry<String, List<String>> entry : specFilters.entrySet()) {
            boolean matchesAny = false;
            for (String value : entry.getValue()) {
                if (matchesSpecFilter(card, entry.getKey(), value)) {
                    matchesAny = true;
                    break;
                }
            }
            if (!matchesAny) {
                return false;
            }
        }
        return true;
    }

    private String specDisplayName(String key) {
        return switch (key) {
            case "ram" -> "RAM";
            case "storage" -> "Storage";
            case "processor" -> "Processor";
            case "screenSize" -> "Screen Size";
            case "resolution" -> "Resolution";
            case "displayType" -> "Display Type";
            case "refreshRate" -> "Refresh Rate";
            case "tonnage" -> "Tonnage";
            case "starRating" -> "Star Rating";
            case "inverter" -> "Inverter";
            case "acType" -> "AC Type";
            case "capacity" -> "Capacity";
            case "doorType" -> "Door Type";
            case "loadType" -> "Load Type";
            case "cameraType" -> "Camera Type";
            case "resolutionMP" -> "Resolution";
            case "headphoneType" -> "Type";
            case "wireless" -> "Wireless";
            case "anc" -> "ANC";
            case "watchDisplay" -> "Display";
            case "gps" -> "GPS";
            case "cellular" -> "Calling";
            case "fiveG" -> "5G";
            default -> key;
        };
    }

    private String specLabel(String key, String value) {
        if (value == null || value.isBlank()) return key;
        return switch (key) {
            case "ram", "storage" -> value + " GB";
            case "refreshRate", "resolutionMP", "starRating" -> value + " " + specDisplayName(key);
            case "screenSize" -> value + " inch";
            case "tonnage" -> value + " Ton";
            case "wireless", "anc", "inverter", "gps", "cellular", "fiveG" -> specDisplayName(key);
            default -> value;
        };
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    /**
     * Per-provider discovery outcome carried back from the executor threads.
     * A failed provider yields an empty outcome that still leaves room for
     * every other registered provider to contribute.
     */
    private record CandidateOutcome(boolean failed, List<DiscoveredProduct> products) {
        CandidateOutcome(List<DiscoveredProduct> products) {
            this(false, products);
        }

        CandidateOutcome(Throwable ex) {
            this(true, List.of());
        }
    }
}

