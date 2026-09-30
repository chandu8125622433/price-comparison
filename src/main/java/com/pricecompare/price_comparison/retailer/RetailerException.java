package com.pricecompare.price_comparison.retailer;

/**
 * Raised when a retailer fetch fails (network, blocked page, absent price, etc.).
 * Deliberately unchecked: a single retailer failure must never crash the app.
 */
public class RetailerException extends RuntimeException {

    public RetailerException(String message) {
        super(message);
    }

    public RetailerException(String message, Throwable cause) {
        super(message, cause);
    }
}