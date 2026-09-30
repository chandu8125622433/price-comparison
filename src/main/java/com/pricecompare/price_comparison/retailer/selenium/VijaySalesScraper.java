package com.pricecompare.price_comparison.retailer.selenium;

import com.pricecompare.price_comparison.retailer.RetailerException;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Selenium-backed scraper for publicly accessible Vijay Sales product pages.
 *
 * <p>Selenium/WebDriver types exist ONLY in this package; the returned
 * {@link PageData} is a clean, normalized snapshot.</p>
 *
 * <p>No login, CAPTCHA-solving, or anti-bot workarounds are attempted. If the
 * retailer gates the page, extraction reports an unusable page rather than
 * bypassing the gate.</p>
 *
 * <p>Deliberately category-independent: it targets the generic Vijay Sales
 * product template used by every category (TV, mobile, laptop, AC, etc.).</p>
 */
@Component
public class VijaySalesScraper implements BrowserScraper {

    /*
     * Selling price. The `.offer` node is a placeholder that may carry
     * data-final-price="null"; it is excluded so the real selling-price node
     * is selected. "null" is NEVER parsed as a price.
     */
    private static final String SELLING_PRICE_CSS =
            "p.product__price--price:not(.offer)[data-final-price]";

    // Generic product-page facts present in the shared PDP template.
    private static final String SKU_CSS = "[data-compare-sku]";
    private static final String PRODUCT_SKU_CSS = "[data-product-sku]";
    private static final String MRP_CSS = "[data-mrp]";
    private static final String DISCOUNT_CSS = "p.product__price--discount-label";
    private static final String ADD_TO_CART_CSS = "button.add-to-cart:not(.d-none)";
    private static final String NOTIFY_ME_CSS = "button.notify__btn:not(.d-none)";
    private static final String IMAGE_CSS = "input[data-compare-img]";

    private static final Duration WAIT_FOR_PRICE = Duration.ofSeconds(12);

    @Override
    public PageData fetchPage(String productUrl) {
        WebDriver driver = SeleniumSession.open(productUrl);
        try {
            if (isLoginOrCaptchaWall(driver)) {
                return new PageData(
                        productUrl, "", "", true, "", "", "", "", "");
            }

            waitForSellingPrice(driver);

            String title = Optional.ofNullable(driver.getTitle()).orElse("");
            String finalPrice = findSellingPrice(driver);

            return new PageData(
                    productUrl,
                    title.trim(),
                    finalPrice.trim(),
                    false,
                    findSku(driver),
                    findAttribute(driver, MRP_CSS, "data-mrp"),
                    findText(driver, DISCOUNT_CSS),
                    findAvailability(driver),
                    findImageUrl(driver)
            );
        } finally {
            SeleniumSession.quit(driver);
        }
    }

    /*
     * Waits until a real selling-price (non-offer) node is present. Throws a
     * controlled RetailerException when the page never exposes one, so the
     * caller sees "price unavailable" instead of a parsing crash.
     */
    private void waitForSellingPrice(WebDriver driver) {
        try {
            driver.manage().timeouts().implicitlyWait(WAIT_FOR_PRICE);
            driver.findElement(By.cssSelector(SELLING_PRICE_CSS));
        } catch (Exception ex) {
            throw new RetailerException(
                    "Vijay Sales page did not expose the selling-price element within the wait window.", ex);
        } finally {
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(0));
        }
    }

    /*
     * Returns the selling price, picking the first non-offer node with a usable
     * value. Defensively ignores blank/"null" attributes and falls back to text
     * so a placeholder is never returned.
     */
    private String findSellingPrice(WebDriver driver) {
        try {
            List<WebElement> nodes = driver.findElements(By.cssSelector(SELLING_PRICE_CSS));
            for (WebElement node : nodes) {
                String value = node.getAttribute("data-final-price");
                if (isUnusable(value)) {
                    value = node.getText();
                }
                if (!isUnusable(value)) {
                    return value;
                }
            }
        } catch (Exception ignored) {
            // no usable price node
        }
        return "";
    }

    private boolean isUnusable(String value) {
        return value == null || value.isBlank() || value.trim().equalsIgnoreCase("null");
    }

    private String findSku(WebDriver driver) {
        String value = findAttribute(driver, SKU_CSS, "data-compare-sku");
        return value.isBlank()
                ? findAttribute(driver, PRODUCT_SKU_CSS, "data-product-sku")
                : value;
    }

    private String findAttribute(WebDriver driver, String css, String attribute) {
        try {
            WebElement el = driver.findElement(By.cssSelector(css));
            String raw = el.getAttribute(attribute);
            return raw == null ? "" : raw.trim();
        } catch (Exception ex) {
            return "";
        }
    }

    private String findText(WebDriver driver, String css) {
        try {
            WebElement el = driver.findElement(By.cssSelector(css));
            return el.getText() == null ? "" : el.getText().trim();
        } catch (Exception ex) {
            return "";
        }
    }

    /*
     * Availability is JS-driven on Vijay Sales (buttons toggled with `.d-none`).
     * Read the rendered DOM: active "Add to Cart" -> in stock; active
     * "Notify Me" -> out of stock; otherwise unknown ("").
     */
    private String findAvailability(WebDriver driver) {
        if (!findElements(driver, ADD_TO_CART_CSS).isEmpty()) {
            return "In stock";
        }
        if (!findElements(driver, NOTIFY_ME_CSS).isEmpty()) {
            return "Out of stock";
        }
        return "";
    }

    private List<WebElement> findElements(WebDriver driver, String css) {
        try {
            return driver.findElements(By.cssSelector(css));
        } catch (Exception ex) {
            return List.of();
        }
    }

    /*
     * Product image is exposed as a full (escaped) URL on the compare checkbox
     * via data-compare-img; HTML entities are decoded.
     */
    private String findImageUrl(WebDriver driver) {
        String raw = findAttribute(driver, IMAGE_CSS, "data-compare-img");
        if (raw.isBlank()) {
            return "";
        }
        return decodeHtmlEntities(raw);
    }

    private String decodeHtmlEntities(String value) {
        return value
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private boolean isLoginOrCaptchaWall(WebDriver driver) {
        try {
            String source = driver.getPageSource().toLowerCase(Locale.ROOT);
            return source.contains("captcha")
                    || source.contains("enter the characters")
                    || source.contains("sign in to continue")
                    || source.contains("log in to continue");
        } catch (Exception ex) {
            return true; // treat unreadable walled content as gated
        }
    }
}