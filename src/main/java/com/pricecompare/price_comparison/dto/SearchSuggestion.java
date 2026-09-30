package com.pricecompare.price_comparison.dto;

import java.util.List;

/**
 * Search-autocomplete suggestions for a partial query. Suggestions route to
 * the appropriate category or brand+category search.
 */
public record SearchSuggestion(
        String query,
        List<Suggestion> suggestions
) {
    public record Suggestion(
            String text,
            SuggestionType type,
            String category
    ) {}

    public enum SuggestionType {
        CATEGORY,
        BRAND_CATEGORY
    }
}
