package com.pricecompare.price_comparison.retailer.provider;

import com.pricecompare.price_comparison.retailer.RetailerException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.retailer.RetailerPrice;
import com.pricecompare.price_comparison.retailer.RetailerPriceProvider;
import com.pricecompare.price_comparison.retailer.selenium.PageData;
import com.pricecompare.price_comparison.retailer.selenium.VijaySalesScraper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Real retailer provider (proof of concept) for publicly accessible
 * Vijay Sales product pages. Returns ONLY normalized {@link RetailerPrice}.
 *
 * <p>No CAPTCHA/login bypass is performed: if the page is gated, the provider
 * reports the blocker instead of trying to work around it.</p>
 */
@Component
public class VijaySalesRetailerPriceProvider implements RetailerPriceProvider {

    private static final String ALLOWED_HOST = "www.vijaysales.com";

    private final VijaySalesScraper scraper;

    public VijaySalesRetailerPriceProvider(VijaySalesScraper scraper) {
        this.scraper = scraper;
    }

    @Override
    public RetailerKey retailer() {
        return RetailerKey.VIJAY_SALES;
    }

    @Override
    public List<RetailerPrice> fetchPrice(String productUrl) {
        requirePublicVijaySalesUrl(productUrl);

        PageData page = scraper.fetchPage(productUrl);

        if (page.hasCaptchaOrLoginWall()) {
            throw new RetailerException(
                    "Vijay Sales presented a CAPTCHA/login wall for " + productUrl
                            + ". Aborting per policy; no bypass attempted.");
        }
        if (page.finalPrice() == null || page.finalPrice().isBlank()) {
            throw new RetailerException(
                    "Vijay Sales page loaded but no selling price was found at " + productUrl);
        }

        BigDecimal price = parsePrice(page.finalPrice());
        String cleanTitle = cleanTitle(page.pageTitle());

        RetailerPrice result = new RetailerPrice(
                RetailerKey.VIJAY_SALES,
                price,
                page.pageUrl(),
                cleanTitle,
                LocalDateTime.now(),
                page.sku()
        );
        return List.of(result);
    }

    private void requirePublicVijaySalesUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new RetailerException("Vijay Sales URL must not be blank.");
        }
        String host;
        try {
            host = new java.net.URI(url).getHost();
        } catch (Exception ex) {
            throw new RetailerException("Invalid Vijay Sales URL: " + url, ex);
        }
        if (host == null || !ALLOWED_HOST.equalsIgnoreCase(host)) {
            throw new RetailerException(
                    "Refusing to fetch non-Vijay-Sales host '" + host + "' (received " + url + ").");
        }
    }

    /*
     * Parses a raw selling-price string into a positive BigDecimal.
     * Category-independent and defensive: any non-priced input (null, blank,
     * the literal "null" offer marker, "-", "₹"-only, malformed or non-numeric
     * text, non-positive values) is turned into a controlled
     * {@link RetailerException} ("price unavailable") instead of a parsing
     * crash. Package-private/static so it can be unit-tested directly.
     */
    static BigDecimal parsePrice(String raw) {
        if (raw == null || raw.isBlank()) {
            throw priceUnavailable("price text is blank/missing");
        }
        String candidate = raw.trim();

        // Reject the literal "null" marker produced by offer-placeholder nodes.
        if (candidate.equalsIgnoreCase("null")) {
            throw priceUnavailable("price marker resolved to literal 'null' (offer placeholder)");
        }
        // Reject a leading minus before digit-stripping, otherwise a text value
        // like "-16090" would silently drop the sign and parse as positive.
        if (candidate.startsWith("-") || candidate.startsWith("−") || candidate.startsWith("–")) {
            throw priceUnavailable("negative/zero-price text: '" + raw + "'");
        }
        // Reject common non-priced placeholders before stripping.
        if (candidate.equals("-") || candidate.equals("—") || candidate.equals("–")
                || candidate.equalsIgnoreCase("n/a") || candidate.equalsIgnoreCase("na")) {
            throw priceUnavailable("non-priced placeholder text: '" + raw + "'");
        }

        // Keep digits and the decimal separator; drop currency symbol, commas, spaces.
        String digits = candidate
                .replace("₹", "")
                .replace(",", "")
                .replaceAll("\\s+", "")
                .replaceAll("[^0-9.]", "");

        if (digits.isEmpty() || digits.equals(".")) {
            throw priceUnavailable("no numeric value in price text: '" + raw + "'");
        }
        if (digits.indexOf('.') != digits.lastIndexOf('.')) {
            throw priceUnavailable("malformed price (multiple decimal separators): '" + raw + "'");
        }

        try {
            BigDecimal value = new BigDecimal(digits);
            if (value.signum() <= 0) {
                throw priceUnavailable("non-positive price: '" + raw + "'");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw priceUnavailable("cannot parse price from text: '" + raw + "'");
        }
    }

    private static RetailerException priceUnavailable(String detail) {
        return new RetailerException("Vijay Sales price unavailable: " + detail);
    }

    private String cleanTitle(String pageTitle) {
        if (pageTitle == null || pageTitle.isBlank()) {
            return "";
        }
        String cleaned = pageTitle
                .replaceAll("(?i)\\s*\\|\\s*Vijay Sales\\s*$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.toLowerCase(Locale.ROOT).startsWith("buy ")
                ? cleaned.substring(4).trim()
                : cleaned;
    }
}