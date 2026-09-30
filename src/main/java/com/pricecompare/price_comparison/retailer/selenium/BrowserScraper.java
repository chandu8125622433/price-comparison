package com.pricecompare.price_comparison.retailer.selenium;

/**
 * Browser-scraping seam. Only this package knows about Selenium.
 *
 * Implementations fetch a public product page and return normalized {@link PageData}.
 * No WebDriver, WebElement, or HTML escapes this interface.
 */
public interface BrowserScraper {

    PageData fetchPage(String productUrl);
}