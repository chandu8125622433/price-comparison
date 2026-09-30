package com.pricecompare.price_comparison.retailer.selenium;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test-only Selenium utility that attaches to an already-running Chrome instance
 * via the Chrome DevTools Protocol (CDP) remote-debugging port.
 *
 * <p>This test does NOT launch a new browser — it reuses the existing Chrome session
 * at {@code localhost:9222}. It deliberately avoids closing the user's browser;
 * the driver is only detached, never quit.
 *
 * <p>No production code is modified. This is a standalone test class verifying
 * that Selenium can connect to an externally-started Chrome with remote debugging enabled.
 */
class ChromeDebugAttachmentTest {

    private static final String DEBUG_ADDRESS = "localhost:9222";

    /**
     * Attaches to the ALREADY-RUNNING Chrome via the CDP debugger address, locates the
     * existing Google tab, reads its URL/title and asserts the connection succeeded.
     *
     * <p>The user's browser is NEVER terminated: instead of {@code driver.quit()} —
     * which, when attached via debuggerAddress, WOULD close the user's real Chrome —
     * the test stops only the chromedriver helper service, detaching the session.
     */
    @Test
    void attachesToRunningChromeAndReadsPageInfo() {
        // Environment precondition: an externally started Chrome must be listening on
        // the remote-debugging port. If it is not running, SKIP (do not fail) — this is
        // an environment-dependent integration check, not an application defect.
        assumeTrue(debugPortOpen(),
                "Chrome remote-debugging port 9222 is not listening; start Chrome with "
                        + "--remote-debugging-port=9222 to run this test");

        ChromeDriverService service = new ChromeDriverService.Builder().usingAnyFreePort().build();
        ChromeDriver driver = null;
        boolean serviceStartedByThisTest = false;
        try {
            try {
                service.start();
                serviceStartedByThisTest = true;
                driver = new ChromeDriver(service, chromeOptions());
            } catch (Exception serviceRouteFailed) {
                // Fallback: implicit Selenium-Manager-managed service (same resolution
                // path used by SeleniumSession). The idle chromedriver may linger, but
                // the user's Chrome is still never closed.
                driver = new ChromeDriver(chromeOptions());
            }

            assertNotNull(driver, "Selenium must attach to the running Chrome instance");

            // Locate the existing Google tab among the open tabs (never open a new one,
            // never navigate). Switching tabs is required to read each tab's URL.
            String googleHandle = null;
            List<String> openTabUrls = new ArrayList<>();
            for (String handle : driver.getWindowHandles()) {
                driver.switchTo().window(handle);
                String tabUrl = driver.getCurrentUrl();
                openTabUrls.add(tabUrl);
                if (tabUrl.contains("google.com")) {
                    googleHandle = handle;
                    break;
                }
            }

            assertNotNull(googleHandle,
                    "No google.com tab found in the running Chrome. Open tabs: " + openTabUrls);

            // Attached to the existing Google tab — READ ONLY, no navigation.
            String currentUrl = driver.getCurrentUrl();
            String pageTitle = driver.getTitle();

            System.out.println("[ChromeDebugAttachmentTest] CDP attach : SUCCESS on " + DEBUG_ADDRESS);
            System.out.println("[ChromeDebugAttachmentTest] Detected URL  : " + currentUrl);
            System.out.println("[ChromeDebugAttachmentTest] Detected title: " + pageTitle);

            assertTrue(currentUrl.contains("google.com"),
                    "Expected URL to contain google.com, got: " + currentUrl);
            assertTrue(pageTitle != null && pageTitle.contains("Google"),
                    "Expected title to contain Google, got: " + pageTitle);

        } finally {
            // DETACH ONLY: stopping the chromedriver helper ends the automation session
            // while the attached user Chrome keeps running. driver.quit() is deliberately
            // NOT called — it would close the user's browser.
            if (serviceStartedByThisTest && service.isRunning()) {
                service.stop();
            }
        }
    }

    private static ChromeOptions chromeOptions() {
        ChromeOptions options = new ChromeOptions();
        // Attach to the existing Chrome instance via the CDP debugger address
        options.setExperimentalOption("debuggerAddress", DEBUG_ADDRESS);
        return options;
    }

    private static boolean debugPortOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 9222), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
