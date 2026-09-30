package com.pricecompare.price_comparison.retailer.discovery;

import com.pricecompare.price_comparison.retailer.RetailerKey;

import java.util.List;

/**
 * Discovery port implemented by retailer integrations that can resolve
 * public product-page URLs from a free-text search query.
 *
 * <p>Implementations return only normalized {@link DiscoveredProduct}
 * records. HTTP/Selenium details and site-specific types never escape the
 * implementing package. No CAPTCHA/login/anti-bot bypass is ever performed:
 * if a retailer blocks discovery, implementations raise a controlled
 * failure instead of working around the protection.</p>
 */
public interface ProductDiscoveryProvider {

    /**
     * Canonical retailer this provider discovers products for.
     */
    RetailerKey retailer();

    /**
     * Resolves candidate public product pages for the given query.
     *
     * @param query free-text search query (e.g. "Samsung 55 inch 4K Smart TV")
     * @return de-duplicated, size-limited candidates; empty when nothing found
     * @throws com.pricecompare.price_comparison.retailer.RetailerException
     *         on controlled discovery failure (network, blocked, unparsable)
     */
    List<DiscoveredProduct> discover(String query);

    default int discoverTotalCount(String query) {
        return -1;
    }
}
