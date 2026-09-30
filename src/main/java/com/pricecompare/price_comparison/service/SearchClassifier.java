package com.pricecompare.price_comparison.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic classifier that distinguishes a CATEGORY query ("mobiles",
 * "laptop") from a SPECIFIC product query ("Samsung S25", "LG AS-Q20ENZE").
 *
 * <p>A query is a CATEGORY query when its normalized form matches a known
 * category alias. Everything else is treated as a SPECIFIC product query and
 * keeps the existing cache-first flow.</p>
 *
 * <p>No NLP, no ML — just an explicit alias map so behavior is predictable
 * and unit-testable.</p>
 */
@Component
public class SearchClassifier {

    public enum SearchType { CATEGORY, SPECIFIC }

    public enum Category {
        MOBILE, LAPTOP, TV, AC, REFRIGERATOR, WASHING_MACHINE, HEADPHONES, TABLET, CAMERA, SMARTWATCH, UNKNOWN
    }

    public record Classification(SearchType type, Category category, String canonicalQuery) {}

    /** Alias → canonical category. All lower-case, trimmed. */
    private static final Map<String, Category> ALIASES = Map.ofEntries(
            Map.entry("mobiles", Category.MOBILE),
            Map.entry("mobile", Category.MOBILE),
            Map.entry("smartphones", Category.MOBILE),
            Map.entry("smartphone", Category.MOBILE),
            Map.entry("phones", Category.MOBILE),
            Map.entry("phone", Category.MOBILE),
            Map.entry("cell phones", Category.MOBILE),
            Map.entry("cell phone", Category.MOBILE),
            Map.entry("laptops", Category.LAPTOP),
            Map.entry("laptop", Category.LAPTOP),
            Map.entry("notebooks", Category.LAPTOP),
            Map.entry("notebook", Category.LAPTOP),
            Map.entry("tvs", Category.TV),
            Map.entry("tv", Category.TV),
            Map.entry("televisions", Category.TV),
            Map.entry("television", Category.TV),
            Map.entry("air conditioners", Category.AC),
            Map.entry("air conditioner", Category.AC),
            Map.entry("ac", Category.AC),
            Map.entry("refrigerators", Category.REFRIGERATOR),
            Map.entry("refrigerator", Category.REFRIGERATOR),
            Map.entry("fridge", Category.REFRIGERATOR),
            Map.entry("fridges", Category.REFRIGERATOR),
            Map.entry("washing machines", Category.WASHING_MACHINE),
            Map.entry("washing machine", Category.WASHING_MACHINE),
            Map.entry("headphones", Category.HEADPHONES),
            Map.entry("headphone", Category.HEADPHONES),
            Map.entry("earbuds", Category.HEADPHONES),
            Map.entry("earbud", Category.HEADPHONES),
            Map.entry("earphones", Category.HEADPHONES),
            Map.entry("earphone", Category.HEADPHONES),
            Map.entry("tablets", Category.TABLET),
            Map.entry("tablet", Category.TABLET),
            Map.entry("cameras", Category.CAMERA),
            Map.entry("camera", Category.CAMERA),
            Map.entry("smartwatches", Category.SMARTWATCH),
            Map.entry("smartwatch", Category.SMARTWATCH)
    );

    /** Canonical display query used for retailer discovery per category. */
    private static final Map<Category, String> CATEGORY_QUERY = Map.of(
            Category.MOBILE, "smartphone",
            Category.LAPTOP, "laptop",
            Category.TV, "tv",
            Category.AC, "air conditioner",
            Category.REFRIGERATOR, "refrigerator",
            Category.WASHING_MACHINE, "washing machine",
            Category.HEADPHONES, "headphones",
            Category.TABLET, "tablet",
            Category.CAMERA, "camera",
            Category.SMARTWATCH, "smartwatch"
    );

    private static final Set<String> BRANDS = Set.of(
            "samsung", "apple", "iphone", "xiaomi", "redmi", "oneplus", "oppo",
            "vivo", "realme", "nokia", "nothing", "pixel", "motorola", "asus",
            "honor", "poco", "iqoo", "sony", "lg", "panasonic", "hitachi",
            "godrej", "bosch", "ifb", "whirlpool", "haier", "carrier", "daikin",
            "blue star", "voltas", "mitsubishi", "boat", "noise", "boult",
            "zebronics", "jbl", "philips", "sennheiser", "akg", "hp", "dell",
            "lenovo", "acer", "microsoft", "razer", "msi", "gigabyte"
    );

    /**
     * Classify a raw user query.
     *
     * @param query raw search input
     * @return classification with type, category and the canonical discovery query
     */
    public Classification classify(String query) {
        if (query == null || query.isBlank()) {
            return new Classification(SearchType.SPECIFIC, Category.UNKNOWN, "");
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);

        // Exact category alias match.
        Category category = ALIASES.get(normalized);
        if (category != null) {
            return new Classification(SearchType.CATEGORY, category,
                    CATEGORY_QUERY.getOrDefault(category, normalized));
        }

        // "{brand} {category}" pattern → category search for that brand.
        for (String alias : ALIASES.keySet()) {
            if (normalized.endsWith(" " + alias)) {
                String leading = normalized.substring(0, normalized.length() - alias.length()).trim();
                boolean leadingIsBrand = !leading.isEmpty() && BRANDS.contains(leading);
                boolean leadingIsPlural = leading.endsWith("s")
                        && BRANDS.contains(leading.substring(0, leading.length() - 1));
                if (leadingIsBrand || leadingIsPlural) {
                    Category cat = ALIASES.get(alias);
                    return new Classification(SearchType.CATEGORY, cat,
                            leading + " " + CATEGORY_QUERY.getOrDefault(cat, alias));
                }
            }
        }

        return new Classification(SearchType.SPECIFIC, Category.UNKNOWN, normalized);
    }

    /** Exposed for autocomplete suggestions. */
    public Map<String, Category> categoryAliases() {
        return ALIASES;
    }
}