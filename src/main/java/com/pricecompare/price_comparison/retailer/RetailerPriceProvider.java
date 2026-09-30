package com.pricecompare.price_comparison.retailer;

import java.util.List;

/**
 * Common port implemented by every retailer integration.
 * Implementations may fetch prices from public web pages, HTML, or JSON APIs,
 * but they MUST return only normalized {@link RetailerPrice} records.
 * Browser internals (Selenium), HTTP details, and scraper-specific types never escape.
 */
public interface RetailerPriceProvider {

    /**
     * Canonical retailer this provider handles.
     */
    RetailerKey retailer();

    /**
     * Fetch and normalize the current price for one public product page.
     *
     * @param productUrl publicly accessible product page URL (no login, no bypass)
     * @return normalized price record (never raw HTML/WebDriver data)
     */
    List<RetailerPrice> fetchPrice(String productUrl);
}