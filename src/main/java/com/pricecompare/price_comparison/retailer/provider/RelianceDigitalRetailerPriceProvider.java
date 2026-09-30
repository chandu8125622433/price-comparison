package com.pricecompare.price_comparison.retailer.provider;

import com.pricecompare.price_comparison.retailer.RetailerException;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.retailer.RetailerPrice;
import com.pricecompare.price_comparison.retailer.RetailerPriceProvider;
import com.pricecompare.price_comparison.retailer.discovery.RelianceDigitalDiscoveryProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Reliance Digital price provider. Fetches ONLY the retailer's public product
 * page (robots-permitted: a plain {@code /product/...} URL without a query
 * string) and parses the static JSON-LD offer data the normal page returns.
 * The {@code ?searchQuery=} search endpoint is never used (robots.txt
 * disallows query-string URLs). No CAPTCHA/anti-bot bypass, no login, no
 * private endpoints; every failure is a controlled {@link RetailerException}
 * and prices are never fabricated.
 */
@Component
public class RelianceDigitalRetailerPriceProvider implements RetailerPriceProvider {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";

    private final RestClient restClient;

    public RelianceDigitalRetailerPriceProvider() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public RetailerKey retailer() {
        return RetailerKey.RELIANCE_DIGITAL;
    }

    @Override
    public List<RetailerPrice> fetchPrice(String productUrl) {
        if (productUrl == null || productUrl.isBlank()
                || !RelianceDigitalDiscoveryProvider.isTrackableProductUrl(productUrl)) {
            throw new RetailerException(
                    "URL is not a valid Reliance Digital product page: " + productUrl);
        }
        try {
            String html = restClient.get()
                    .uri(URI.create(productUrl.trim()))
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .header(HttpHeaders.ACCEPT,
                            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .retrieve()
                    .body(String.class);
            return RelianceDigitalDiscoveryProvider.parseProductPage(html, productUrl);
        } catch (RetailerException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RetailerException("Reliance Digital request failed for " + productUrl, ex);
        }
    }
}