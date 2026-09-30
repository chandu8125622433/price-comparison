package com.pricecompare.price_comparison.retailer;

/**
 * Raised when a retailer integration cannot run because its API credentials
 * or configuration are missing. Distinct from {@link RetailerException} so
 * orchestration layers can report an explicit CONFIGURATION_REQUIRED status
 * (never a fabricated price) while other retailers continue unaffected.
 */
public class RetailerConfigurationException extends RetailerException {

    public RetailerConfigurationException(String message) {
        super(message);
    }
}
