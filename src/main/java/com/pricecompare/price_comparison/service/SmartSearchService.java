package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.dto.CategoryListingResponse;
import com.pricecompare.price_comparison.dto.PriceInfo;
import com.pricecompare.price_comparison.dto.ProductDetailResponse;
import com.pricecompare.price_comparison.dto.SearchSuggestion;
import com.pricecompare.price_comparison.dto.SmartSearchResponse;
import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.entity.ProductPrice;
import com.pricecompare.price_comparison.entity.RetailerLink;
import com.pricecompare.price_comparison.repository.ProductPriceRepository;
import com.pricecompare.price_comparison.repository.ProductRepository;
import com.pricecompare.price_comparison.repository.RetailerLinkRepository;
import com.pricecompare.price_comparison.retailer.ProductIdentityMatcher;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.retailer.RetailerPrice;
import com.pricecompare.price_comparison.retailer.discovery.DiscoveredProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Cache-first product search (Phase "cache-first search + live discovery on
 * miss") plus category product-listing search.
 */
@Service
public class SmartSearchService {

    private static final Logger log = LoggerFactory.getLogger(SmartSearchService.class);

    /** Display names of the five mandatory live retailers. */
    private static final Set<String> LIVE_RETAILER_NAMES = Set.of(
            "Vijay Sales", "Reliance Digital", "Flipkart", "Amazon", "Amazon India", "Croma");

    /** Brands offered in autocomplete brand+category suggestions. */
    private static final List<String> CATEGORY_BRANDS = List.of(
            "samsung", "xiaomi", "apple", "oneplus", "oppo", "vivo", "realme",
            "nokia", "nothing", "poco", "motorola", "sony", "lg", "boat", "jbl");

    private final ProductService productService;
    private final ProductRepository productRepository;
    private final ProductPriceRepository productPriceRepository;
    private final ProductDiscoveryService discoveryService;
    private final com.pricecompare.price_comparison.retailer.RetailerRegistry retailerRegistry;
    private final ProductPriceService productPriceService;
    private final RetailerLinkRepository retailerLinkRepository;
    private final ProductIdentityMatcher identityMatcher;
    private final SearchClassifier searchClassifier;
    private final CategoryDiscoveryService categoryDiscoveryService;
    private final int freshnessMinutes;

    public SmartSearchService(ProductService productService,
                              ProductRepository productRepository,
                              ProductPriceRepository productPriceRepository,
                              ProductDiscoveryService discoveryService,
                              com.pricecompare.price_comparison.retailer.RetailerRegistry retailerRegistry,
                              ProductPriceService productPriceService,
                              RetailerLinkRepository retailerLinkRepository,
                              ProductIdentityMatcher identityMatcher,
                              SearchClassifier searchClassifier,
                              CategoryDiscoveryService categoryDiscoveryService,
                              @Value("${price.cache.freshness-minutes:60}") int freshnessMinutes) {
        this.productService = productService;
        this.productRepository = productRepository;
        this.productPriceRepository = productPriceRepository;
        this.discoveryService = discoveryService;
        this.retailerRegistry = retailerRegistry;
        this.productPriceService = productPriceService;
        this.retailerLinkRepository = retailerLinkRepository;
        this.identityMatcher = identityMatcher;
        this.searchClassifier = searchClassifier;
        this.categoryDiscoveryService = categoryDiscoveryService;
        this.freshnessMinutes = freshnessMinutes;
    }

    /**
     * Main entry point for smart search. Classifies the query and dispatches:
     * - CATEGORY queries -> CategoryDiscoveryService (multi-product listing)
     * - SPECIFIC_PRODUCT queries -> cache-first search + live discovery on miss
     */
    public SmartSearchResponse search(String query) {
        return search(query, 1, 100);
    }

    public SmartSearchResponse search(String query, int page, int pageSize) {
        if (query == null || query.trim().isEmpty()) {
            return SmartSearchResponse.of("EMPTY_QUERY", "Enter a product name to search.", List.of());
        }

        SearchClassifier.Classification classification = searchClassifier.classify(query);
        if (classification.type() == SearchClassifier.SearchType.CATEGORY) {
            SearchClassifier.Category category = classification.category();
            // A PURE category alias ("mobiles", "tv", "laptops") must ALWAYS go
            // to the multi-product category listing. Doing a DB product search
            // here previously pulled in the "1 Year VS Plus Extended Warranty
            // for Mobiles..." seed row -- the exact bug we are fixing.
            if (isPureCategoryAlias(query)) {
                CategoryListingResponse listing = categoryDiscoveryService.discover(category, query, page, pageSize);
                return SmartSearchResponse.ofCategory("CATEGORY_RESULT", "Category search completed.", listing);
            }
            // "{brand} {category}" (e.g. "Samsung TV"): if a real product exists
            // in the database, treat it as a specific-product search so the
            // existing comparison flow keeps working; otherwise show the
            // brand-filtered category listing.
            List<Product> dbHits = productService.searchProducts(query);
            if (!dbHits.isEmpty()) {
                log.info("[smart-search] '{}' classified CATEGORY but a matching DB product exists -- treating as specific search.", query);
                return searchProduct(query, classification);
            }
            CategoryListingResponse listing = categoryDiscoveryService.discover(category, query, page, pageSize);
            return SmartSearchResponse.ofCategory("CATEGORY_RESULT", "Category search completed.", listing);
        }

        // SPECIFIC_PRODUCT flow
        return searchProduct(query, classification);
    }

    /** True when the whole trimmed query is exactly a category alias word. */
    private boolean isPureCategoryAlias(String query) {
        if (query == null || query.isBlank()) return false;
        return searchClassifier.categoryAliases()
                .containsKey(query.trim().toLowerCase(Locale.ROOT));
    }

    private SmartSearchResponse searchProduct(String query, SearchClassifier.Classification classification) {
        List<Product> dbHits = productService.searchProducts(query);
        if (!dbHits.isEmpty()) {
            Product product = dbHits.get(0);
            List<PriceInfo> priceInfos = buildPriceInfos(productPriceRepository.findByProductIdOrderByPriceAsc(product.getId()));
            if (!priceInfos.isEmpty()) {
                boolean fresh = isFresh(priceInfos);
                priceInfos = priceInfos.stream().map(p -> p.withFresh(fresh)).toList();
                String decision = fresh ? "CACHE_HIT_FRESH" : "CACHE_HIT_STALE";
                List<ProductDetailResponse> results = List.of(
                        ProductDetailResponse.from(product, priceInfos)
                );
                return SmartSearchResponse.of(decision, fresh ? "Fresh cache hit." : "Stale cache hit.", results);
            }
        }

        String canonicalQuery = classification.canonicalQuery();
        if (canonicalQuery == null || canonicalQuery.isBlank()) {
            canonicalQuery = query.trim();
        }
        List<DiscoveredProduct> candidates = discoveryService.discoverCandidates(canonicalQuery);
        log.info("[smart-search] DB miss for '{}'; discovered {} candidate(s)", query, candidates.size());

        for (DiscoveredProduct candidate : candidates) {
            // Identity gate: the USER's query is the expected identity; the
            // candidate must actually represent that exact product. Never
            // compare the candidate against itself.
            ProductIdentityMatcher.Decision decision = identityMatcher.evaluateConfirmed(
                    "", canonicalQuery, null,
                    new RetailerPrice(candidate.retailer(), null, candidate.productUrl(),
                            candidate.productTitle(), LocalDateTime.now(), candidate.sku()));
            if (decision.outcome() == ProductIdentityMatcher.Outcome.MATCH
                    || decision.outcome() == ProductIdentityMatcher.Outcome.IDENTITY_UNAVAILABLE) {
                Product saved = findOrCreateProduct(candidate);
                persistRetailerLink(saved, candidate);
                persistVerifiedOffer(saved, candidate);
                List<PriceInfo> priceInfos = buildPriceInfos(productPriceRepository.findByProductIdOrderByPriceAsc(saved.getId()));
                List<ProductDetailResponse> results = List.of(
                        ProductDetailResponse.from(saved, priceInfos)
                );
                return SmartSearchResponse.of("DB_MISS_DISCOVERED",
                        "Discovered and confirmed product: " + saved.getName(), results);
            }
        }

        return SmartSearchResponse.of("DB_MISS_NO_MATCH",
                "No exact product match found for \"" + query + "\".", List.of());
    }

    private boolean isFresh(List<PriceInfo> prices) {
        if (prices.isEmpty()) return false;
        LocalDateTime latest = prices.stream()
                .map(PriceInfo::lastUpdated)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.MIN);
        return latest.isAfter(LocalDateTime.now().minusMinutes(freshnessMinutes));
    }

    private List<PriceInfo> buildPriceInfos(List<ProductPrice> prices) {
        return prices.stream()
                .filter(p -> LIVE_RETAILER_NAMES.contains(p.getWebsite())
                        && RetailerKey.hasOfficialProductUrl(p.getWebsite(), p.getProductUrl()))
                .filter(p -> retailerLinkRepository.findByProductIdAndWebsite(p.getProduct().getId(), p.getWebsite())
                        .filter(link -> link.getSku() != null && p.getSku() != null
                                && link.getSku().equalsIgnoreCase(p.getSku())
                                && link.getProductUrl().equals(p.getProductUrl()))
                        .isPresent())
                .map(PriceInfo::from)
                .toList();
    }

    private Product findOrCreateProduct(DiscoveredProduct candidate) {
        Optional<Product> existing = productRepository.findByNameAndBrandAndModelAndCategory(
                candidate.productTitle(), "", candidate.sku(), "");
        if (existing.isPresent()) return existing.get();
        Product product = new Product(candidate.productTitle(), "", candidate.sku(), "");
        Product saved = productRepository.save(product);
        log.info("[smart-search] persisted discovered product '{}' as product {}",
                candidate.productTitle(), saved.getId());
        return saved;
    }

    private void persistRetailerLink(Product saved, DiscoveredProduct candidate) {
        String website = candidate.retailer().getDisplayName();
        try {
            RetailerLink link = retailerLinkRepository
                    .findByProductIdAndWebsite(saved.getId(), website)
                    .orElseGet(RetailerLink::new);
            link.setProduct(saved);
            link.setWebsite(website);
            link.setProductUrl(candidate.productUrl());
            link.setSku(candidate.sku());
            if (link.getLinkedAt() == null) {
                link.setLinkedAt(LocalDateTime.now());
            }
            retailerLinkRepository.save(link);
        } catch (Exception ex) {
            log.warn("[smart-search] could not persist retailer link for product {}: {}",
                    saved.getId(), ex.getMessage());
        }
    }

    private void persistVerifiedOffer(Product saved, DiscoveredProduct confirmed) {
        try {
            List<RetailerPrice> fetched = retailerRegistry
                    .findByKey(confirmed.retailer())
                    .map(provider -> provider.fetchPrice(confirmed.productUrl()))
                    .orElse(List.of());
            for (RetailerPrice rp : fetched) {
                if (rp.price() == null) continue;
                ProductIdentityMatcher.Decision decision = identityMatcher.evaluateConfirmed(
                        "", confirmed.productTitle(), saved,
                        new RetailerPrice(rp.retailer(), rp.price(), rp.productUrl(),
                                rp.productTitle(), rp.fetchedAt(), rp.sku()));
                if (decision.outcome() == ProductIdentityMatcher.Outcome.MATCH) {
                    String url = (rp.productUrl() == null || rp.productUrl().isBlank())
                            ? confirmed.productUrl() : rp.productUrl();
                    productPriceService.addPrice(saved.getId(), rp.retailer().getDisplayName(),
                            rp.price(), url, rp.sku());
                    log.info("[smart-search] stored verified offer for product {}", saved.getId());
                } else {
                    log.warn("[smart-search] identity mismatch for fetched price: {}", decision.reason());
                }
            }
        } catch (Exception ex) {
            log.warn("[smart-search] could not verify live price for '{}': {}",
                    saved.getName(), ex.getMessage() == null ? "unknown error" : ex.getMessage());
        }
    }

    public CategoryListingResponse categorySearch(String query) {
        return categorySearch(query, 1, 100);
    }

    public CategoryListingResponse categorySearch(String query, int page, int pageSize) {
        return categorySearch(query, page, pageSize, List.of(), null, null, "price-asc");
    }

    public CategoryListingResponse categorySearch(String query, int page, int pageSize, List<String> brands,
                                                          BigDecimal minPrice, BigDecimal maxPrice, String sort) {
        return categorySearch(query, page, pageSize, brands, minPrice, maxPrice, sort, Map.of());
    }

    public CategoryListingResponse categorySearch(String query, int page, int pageSize, List<String> brands,
                                                          BigDecimal minPrice, BigDecimal maxPrice, String sort,
                                                          Map<String, List<String>> specFilters) {
        SearchClassifier.Classification classification = searchClassifier.classify(query);
        SearchClassifier.Category category = classification.category();
        return categoryDiscoveryService.discover(category, query, page, pageSize, brands, minPrice, maxPrice, sort, specFilters);
    }

    public SearchSuggestion suggestions(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new SearchSuggestion(query, List.of());
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        List<SearchSuggestion.Suggestion> suggestions = new ArrayList<>();

        for (String alias : searchClassifier.categoryAliases().keySet()) {
            if (alias.startsWith(normalized) || alias.contains(normalized)) {
                suggestions.add(new SearchSuggestion.Suggestion(alias, SearchSuggestion.SuggestionType.CATEGORY, null));
            }
        }

        // "{brand} {category}" suggestions -- shown both when the query names a
        // category ("mobiles" -> "Samsung mobiles", "Apple mobiles", ...) and when
        // the query is a brand prefix ("sams" -> "Samsung mobiles", ...).
        SearchClassifier.Classification cls = searchClassifier.classify(normalized);
        if (cls.type() == SearchClassifier.SearchType.CATEGORY) {
            String aliasWord = categoryAliasWord(cls.category());
            for (String brand : CATEGORY_BRANDS) {
                suggestions.add(new SearchSuggestion.Suggestion(brand + " " + aliasWord,
                        SearchSuggestion.SuggestionType.BRAND_CATEGORY, aliasWord));
            }
        } else {
            for (String brand : CATEGORY_BRANDS) {
                if (brand.startsWith(normalized)) {
                    suggestions.add(new SearchSuggestion.Suggestion(brand + " mobiles",
                            SearchSuggestion.SuggestionType.BRAND_CATEGORY, "mobiles"));
                }
            }
        }

        return new SearchSuggestion(query, suggestions);
    }

    /** Canonical alias used to build "{brand} {alias}" suggestion strings. */
    private static String categoryAliasWord(SearchClassifier.Category category) {
        return switch (category) {
            case MOBILE -> "mobiles";
            case LAPTOP -> "laptops";
            case TV -> "tvs";
            case AC -> "air conditioners";
            case REFRIGERATOR -> "refrigerators";
            case WASHING_MACHINE -> "washing machines";
            case HEADPHONES -> "headphones";
            default -> "products";
        };
    }
}
