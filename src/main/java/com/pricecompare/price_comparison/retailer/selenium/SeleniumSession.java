package com.pricecompare.price_comparison.retailer.selenium;

import com.pricecompare.price_comparison.retailer.RetailerException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.time.Duration;

/**
 * Owns the Selenium WebDriver lifecycle. All driver configuration,
 * startup, teardown, and Selenium imports stay confined to this class
 * so that no WebDriver object ever escapes the selenium package.
 *
 * <p>No anti-bot workarounds are performed: pages are fetched exactly as a
 * normal browser would, and if a retailer presents a CAPTCHA or login wall
 * this code does NOT bypass it — it reports the page as not usable.</p>
 */
public final class SeleniumSession {

    private SeleniumSession() {
    }

    /**
     * Opens a Chromium-based driver against the given URL using ordinary,
     * publicly visible browser options. Returns the rendered page title.
     * The caller is responsible for {@link #quit(WebDriver)} in a finally block.
     *
     * @throws RetailerException if the driver cannot start or the page cannot be read
     */
    public static WebDriver open(String pageUrl) {
        ChromeOptions options = new ChromeOptions();
        options.addArguments(
                "--headless=new",
                "--disable-gpu",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--window-size=1920,1080",
                "--lang=en-IN"
        );
        options.setPageLoadTimeout(Duration.ofSeconds(45));
        options.setImplicitWaitTimeout(Duration.ofSeconds(0));

        WebDriver driver;
        try {
            driver = new ChromeDriver(options);
        } catch (Exception ex) {
            throw new RetailerException("Unable to launch ChromeDriver. " +
                    "Install/align ChromeDriver with local Chrome, or test on a supported host.", ex);
        }

        try {
            driver.get(pageUrl);
            return driver;
        } catch (Exception ex) {
            quit(driver);
            throw new RetailerException("Failed to load public page: " + pageUrl, ex);
        }
    }

    /**
     * Records the visible page state and closes the driver safely.
     */
    public static String currentPageSource(WebDriver driver) {
        try {
            return driver.getPageSource();
        } catch (Exception ex) {
            throw new RetailerException("Failed to read rendered page source.", ex);
        }
    }

    public static void quit(WebDriver driver) {
        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception ignored) {
                // best-effort cleanup; never mask the original failure
            }
        }
    }
}