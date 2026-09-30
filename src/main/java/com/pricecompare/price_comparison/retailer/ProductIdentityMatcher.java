package com.pricecompare.price_comparison.retailer;

import com.pricecompare.price_comparison.entity.Product;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Conservative, deterministic identity guard between a persisted {@link Product}
 * and a freshly scraped {@link RetailerPrice}.
 *
 * <p>Prevents a retailer price from being saved against the wrong Product
 * (e.g. a product whose stored Vijay Sales URL actually points at a different
 * product). Matching is deliberately NOT fuzzy, so it must never create a false
 * positive that would associate a price with the wrong product:</p>
 *
 * <ol>
 *   <li><b>SKU path</b> — when BOTH sides carry a trusted SKU, exact
 *       (case-insensitive) equality is decisive.</li>
 *   <li><b>Title path</b> — otherwise a conservative containment check: every
 *       brand token must appear in the normalized scraped title, and at least
 *       one non-generic product-name/model token must appear too.</li>
 *   <li>If there is nothing to compare against (blank scraped title + no SKU,
 *       or a blank expected identity), the outcome is {@code IDENTITY_UNAVAILABLE}
 *       and callers must fail closed — do NOT persist.</li>
 * </ol>
 *
 * <p>Today {@link Product} has no SKU column, so the title path is the operative
 * guard; the SKU path is exercised by callers that do have a trusted SKU and is
 * covered by unit tests.</p>
 */
@Component
public final class ProductIdentityMatcher {

    public enum Outcome { MATCH, MISMATCH, IDENTITY_UNAVAILABLE }

    public record Decision(Outcome outcome, String reason) {
    }

    private static final Set<String> GENERIC_TOKENS = Set.of(
            "tv", "television", "led", "lcd", "oled", "qled", "hd", "fhd", "uhd",
            "4k", "8k", "smart", "inch", "cm", "inches", "the", "a", "an", "and",
            "with", "for", "of", "to", "on", "online", "buy", "new", "latest",
            "best", "model", "price", "mrp", "in", "at", "black", "white", "grey",
            "gray", "wifi", "bluetooth", "ram", "gb", "tb"
    );

    /**
     * Product-family markers (Phase 7). If the requested product's name/model
     * contains one of these, the scraped title must contain the SAME marker
     * before the match may proceed — a different family is an automatic
     * mismatch (e.g. macbook vs ipad, iphone vs ipad, watch vs buds).
     */
    private static final Set<String> FAMILY_TOKENS = Set.of(
            "iphone", "ipad", "macbook", "imac", "airpods", "watch", "galaxy",
            "buds", "tab"
    );

    private static final Locale LOC = Locale.ROOT;

    /**
     * Convenience for the common case where the requested product has no
     * persisted SKU (the current schema has no SKU column). Equivalent to
     * {@link #evaluate(String, Product, RetailerPrice)} with an empty
     * {@code expectedSku}.
     */
    public Decision evaluate(Product product, RetailerPrice scraped) {
        return evaluate("", product, scraped);
    }

    /**
     * Evaluates whether the scraped retailer product corresponds to the
     * requested product.
     *
     * @param expectedSku trusted SKU of the requested product; pass {@code ""}
     *                    when not available so the title path is used
     * @param product     the persisted product a price update was requested for
     * @param scraped     the scraped {@link RetailerPrice}
     */
    /**
     * Model-strict evaluation against the USER-CONFIRMED product identity.
     *
     * <p>Used by the multi-retailer comparison so that a retailer result is
     * only reported when it is provably the SAME product the user confirmed
     * (e.g. LG AS-Q20ENZE), never a near-variant of the same brand/family:</p>
     *
     * <ol>
     *   <li><b>SKU path</b> — when both the expected (confirmed link) SKU and
     *       the scraped SKU exist, exact equality is decisive.</li>
     *   <li><b>Confirmed-title path</b> — when a confirmed candidate title is
     *       available (it carries the exact model code), EVERY strong
     *       (non-generic) token of that title must appear in the scraped
     *       title. This rejects different models of the same brand:
     *       AS-Q20ENZE vs AS-Q19CNZE1 / AS-Q19CWZE1 / LS-Q18MNZA.</li>
     *   <li>Otherwise falls back to the legacy
     *       {@link #evaluate(String, Product, RetailerPrice)} behaviour so
     *       pre-existing flows are unchanged.</li>
     * </ol>
     *
     * @param expectedSku   trusted SKU of the confirmed link ("" when none)
     * @param expectedTitle title of the user-confirmed candidate ("" when none)
     * @param product       the persisted product
     * @param scraped       the retailer-side result being validated
     */
    public Decision evaluateConfirmed(String expectedSku, String expectedTitle,
                                      Product product, RetailerPrice scraped) {
        String sku = expectedSku == null ? "" : expectedSku.trim();
        String scrapedSku = normalizeSku(scraped.sku());

        // 1. SKU path: decisive when both sides carry a trusted SKU.
        if (!sku.isEmpty() && !scrapedSku.isEmpty()) {
            return sku.equalsIgnoreCase(scrapedSku)
                    ? new Decision(Outcome.MATCH, null)
                    : new Decision(Outcome.MISMATCH,
                            "Retailer product mismatch: SKU expected '" + sku
                                    + "' but scraped '" + scrapedSku + "'.");
        }

        // 2. Confirmed-title path: model-strict containment, never fuzzy.
        Set<String> required = new HashSet<>(tokens(expectedTitle));
        required.removeAll(GENERIC_TOKENS);
        if (!required.isEmpty()) {
            Set<String> scrapedTokens = tokens(scraped.productTitle());
            if (!scrapedTokens.containsAll(required)) {
                Set<String> missing = new HashSet<>(required);
                missing.removeAll(scrapedTokens);
                return new Decision(Outcome.MISMATCH,
                        mismatchMessage(product, scraped)
                                + " Confirmed-model tokens missing: " + missing
                                + " (model-strict match; a different model is rejected).");
            }
            return new Decision(Outcome.MATCH, null);
        }

        // 3. No confirmed identity available: legacy conservative behaviour.
        return evaluate(sku, product, scraped);
    }

    public Decision evaluate(String expectedSku, Product product, RetailerPrice scraped) {
        String scrapedSku = normalizeSku(scraped.sku());

        // SKU path: decisive ONLY when both sides have a trusted SKU.
        if (expectedSku != null && !expectedSku.isBlank() && !scrapedSku.isEmpty()) {
            if (expectedSku.trim().equalsIgnoreCase(scrapedSku)) {
                return new Decision(Outcome.MATCH, null);
            }
            return new Decision(Outcome.MISMATCH,
                    "Retailer product mismatch: SKU expected '" + expectedSku.trim()
                            + "' but scraped '" + scrapedSku + "'.");
        }

        // Title path: conservative containment, never fuzzy.
        Set<String> brandTokens = tokens(product.getBrand());
        Set<String> nameTokens = tokens(product.getName());
        Set<String> modelTokens = tokens(product.getModel());
        Set<String> scrapedTokens = tokens(scraped.productTitle());

        if (scrapedTokens.isEmpty()) {
            return new Decision(Outcome.IDENTITY_UNAVAILABLE,
                    "Retailer product identity unavailable: scraped title is blank and no SKU "
                            + "is available; skipping persistence to avoid mis-association.");
        }

        /*
         * Product-family conflict guard (Phase 7): if the requested product
         * names a specific product family (macbook, iphone, ipad, watch, ...),
         * the scraped title must belong to that SAME family. This rejects
         * false positives such as "Apple MacBook Air M3" vs "APPLE IPAD AIR
         * M3 2025", which previously passed on shared generic tokens
         * (apple + air + m3) alone.
         */
        Set<String> productFamily = new HashSet<>(nameTokens);
        productFamily.addAll(modelTokens);
        productFamily.retainAll(FAMILY_TOKENS);
        if (!productFamily.isEmpty()) {
            Set<String> scrapedFamily = new HashSet<>(scrapedTokens);
            scrapedFamily.retainAll(FAMILY_TOKENS);
            boolean sameFamily = !scrapedFamily.isEmpty() && scrapedFamily.containsAll(productFamily);
            if (!sameFamily) {
                return new Decision(Outcome.MISMATCH,
                        mismatchMessage(product, scraped)
                                + " Product family conflict: requested "
                                + productFamily + " but scraped " + scrapedFamily + ".");
            }
        }

        // Anchor tokens: non-generic product name/model tokens, excluding brand/stopwords.
        Set<String> anchorTokens = new HashSet<>(nameTokens);
        anchorTokens.addAll(modelTokens);
        anchorTokens.removeAll(GENERIC_TOKENS);
        anchorTokens.removeAll(brandTokens);

        if (brandTokens.isEmpty() && anchorTokens.isEmpty()) {
            return new Decision(Outcome.IDENTITY_UNAVAILABLE,
                    "Retailer product identity unavailable: requested product has no usable "
                            + "brand/name/model identity tokens; skipping persistence.");
        }

        // Every brand token must appear in the scraped title (strong guard).
        for (String brandToken : brandTokens) {
            if (!scrapedTokens.contains(brandToken)) {
                return new Decision(Outcome.MISMATCH, mismatchMessage(product, scraped));
            }
        }

        // At least one non-generic product-name/model token must appear, so a
        // shared brand alone (different model within the same brand) is rejected.
        boolean foundAnchor = false;
        for (String anchor : anchorTokens) {
            if (scrapedTokens.contains(anchor)) {
                foundAnchor = true;
                break;
            }
        }
        if (!foundAnchor) {
            return new Decision(Outcome.MISMATCH, mismatchMessage(product, scraped));
        }

        return new Decision(Outcome.MATCH, null);
    }
    private static String mismatchMessage(Product product, RetailerPrice scraped) {
        // product may be null when the expected identity comes from a free-text
        // user query (DB-miss discovery flow) rather than a persisted Product.
        String requested = product == null ? ""
                : firstNonBlank(product.getName(), product.getBrand(), product.getModel());
        String scrapedTitle = scraped.productTitle() == null ? "" : scraped.productTitle().trim();
        return "Retailer product mismatch: requested "
                + (requested.isEmpty() ? "(unknown)" : requested)
                + " but scraped "
                + (scrapedTitle.isEmpty() ? "(no title)" : scrapedTitle)
                + ".";
    }

    private static String normalizeSku(String sku) {
        return sku == null ? "" : sku.trim().toLowerCase(LOC);
    }

    private static Set<String> tokens(String value) {
        Set<String> out = new HashSet<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String raw : value.toLowerCase(LOC).split("[^a-z0-9]+")) {
            if (!raw.isEmpty()) {
                out.add(raw);
            }
        }
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}