package com.pricecompare.price_comparison.retailer.selenium;

/**
 * Normalized, framework-free snapshot of a fetched public product page.
 * Contains only primitive/standard data — never WebDriver, WebElement,
 * HTML strings, or Selenium classes outside this package.
 *
 * <p>Fields are category-independent and best-effort: any extraction that is
 * absent on a given page (e.g. no MRP offered, availability is JS-driven)
 * is returned as an empty string rather than failing the fetch.</p>
 */
public record PageData(
        String pageUrl,
        String pageTitle,
        String finalPrice,
        boolean hasCaptchaOrLoginWall,
        String sku,
        String mrp,
        String discountLabel,
        String availability,
        String imageUrl
) {
}