package com.pricecompare.price_comparison;

import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.entity.ProductPrice;
import com.pricecompare.price_comparison.repository.ProductPriceRepository;
import com.pricecompare.price_comparison.repository.ProductRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Deterministic, idempotent demo-catalog seeding for the MVP.
 *
 * <p><b>SAMPLE/DEMO DATA ONLY.</b> The retailer names and prices below are
 * fictional placeholders on clearly fictional retailers (DemoStore,
 * SampleMart, DemoElectronics, TestStore over example.com URLs). They exist
 * so multi-category search and price comparison can be demonstrated. They
 * are NOT live retailer prices and MUST NOT be presented as coming from
 * Amazon, Flipkart, Croma, Reliance Digital or any other real retailer.
 * Real Vijay Sales prices are only ever produced by the Phase 1-4
 * scraper/refresh pipeline.</p>
 *
 * <p>Everything here is find-or-create by natural key (product:
 * name+brand+model+category; price: product+website), so restarting the
 * application — or running the Spring context test — never duplicates
 * products or price rows, never overwrites existing rows, and never
 * deletes anything. Pre-existing rows (including the known Vijay Sales
 * rows) are left untouched.</p>
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private final ProductRepository productRepository;
    private final ProductPriceRepository productPriceRepository;

    public DataInitializer(ProductRepository productRepository,
                           ProductPriceRepository productPriceRepository) {
        this.productRepository = productRepository;
        this.productPriceRepository = productPriceRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        /* ---------- Existing TV demo (original behavior preserved) ---------- */
        Product samsungTv = seedProductIfAbsent(
                "Samsung 55 inch 4K Smart TV", "Samsung", "UA55", "Television");
        seedPriceIfAbsent(samsungTv, "TechRetail", "74999.00",
                "https://example.com/techretail/samsung-ua55");
        seedPriceIfAbsent(samsungTv, "ShopZone", "69999.00",
                "https://example.com/shopzone/samsung-ua55");
        seedPriceIfAbsent(samsungTv, "ElectroMart", "78999.00",
                "https://example.com/electromart/samsung-ua55");

        /*
         * ---------- Multi-category SAMPLE/DEMO catalog ----------
         * Fictional prices on fictional demo retailers. Category strings are
         * deliberately chosen to stay searchable with the current substring
         * search (e.g. category "AC" so a query for "AC" matches).
         */

        // Mobile
        Product iphone16 = seedProductIfAbsent(
                "Apple iPhone 16", "Apple", "iPhone 16", "Mobile");
        seedDemoPrice(iphone16, "DemoStore", "79999.00", "iphone-16");
        seedDemoPrice(iphone16, "SampleMart", "82999.00", "iphone-16");
        seedDemoPrice(iphone16, "DemoElectronics", "77999.00", "iphone-16");

        // Laptop
        Product macbookAir = seedProductIfAbsent(
                "Apple MacBook Air M3", "Apple", "MacBook Air M3", "Laptop");
        seedDemoPrice(macbookAir, "DemoStore", "114990.00", "macbook-air-m3");
        seedDemoPrice(macbookAir, "SampleMart", "112990.00", "macbook-air-m3");


        // Air Conditioner (category "AC" for substring searchability)
        Product lgAc = seedProductIfAbsent(
                "LG 1.5 Ton 5 Star Split AC", "LG", "LS-Q18YNZA", "AC");
        seedDemoPrice(lgAc, "DemoStore", "42990.00", "lg-1-5-ton-split-ac");
        seedDemoPrice(lgAc, "DemoElectronics", "41990.00", "lg-1-5-ton-split-ac");

        // Refrigerator
        Product samsungFridge = seedProductIfAbsent(
                "Samsung 253L Double Door Refrigerator", "Samsung", "RT28C3723S8", "Refrigerator");
        seedDemoPrice(samsungFridge, "SampleMart", "27990.00", "samsung-253l-refrigerator");
        seedDemoPrice(samsungFridge, "DemoStore", "26990.00", "samsung-253l-refrigerator");

        // Washing Machine
        Product lgWasher = seedProductIfAbsent(
                "LG 8 Kg Front Load Washing Machine", "LG", "FHM1408SDL", "Washing Machine");
        seedDemoPrice(lgWasher, "DemoElectronics", "33990.00", "lg-8kg-front-load-washer");
        seedDemoPrice(lgWasher, "DemoStore", "32990.00", "lg-8kg-front-load-washer");

        // Headphones
        Product sonyHeadphones = seedProductIfAbsent(
                "Sony WH-1000XM5 Wireless Headphones", "Sony", "WH-1000XM5", "Headphones");
        seedDemoPrice(sonyHeadphones, "DemoStore", "29990.00", "sony-wh-1000xm5");
        seedDemoPrice(sonyHeadphones, "SampleMart", "28990.00", "sony-wh-1000xm5");

        // Smartwatch (optional category; the existing structure supports it cleanly)
        Product appleWatch = seedProductIfAbsent(
                "Apple Watch SE GPS 40mm", "Apple", "Watch SE 40mm", "Smartwatch");
        seedDemoPrice(appleWatch, "DemoStore", "29999.00", "apple-watch-se-40mm");
        seedDemoPrice(appleWatch, "SampleMart", "28499.00", "apple-watch-se-40mm");
    }

    /** Find-or-create a product by the same natural key the original seed used. */
    private Product seedProductIfAbsent(String name, String brand, String model, String category) {
        return productRepository
                .findByNameAndBrandAndModelAndCategory(name, brand, model, category)
                .orElseGet(() -> productRepository.save(new Product(name, brand, model, category)));
    }

    /** One demo price row on a clearly fictional retailer (example.com URL). */
    private void seedDemoPrice(Product product, String website, String price, String slug) {
        seedPriceIfAbsent(product, website, price, "https://example.com/demo/" + slug);
    }

    private void seedPriceIfAbsent(Product product,
                                   String website,
                                   String price,
                                   String productUrl) {
        boolean exists = productPriceRepository
                .findByProductIdAndWebsite(product.getId(), website)
                .isPresent();
        if (!exists) {
            productPriceRepository.save(new ProductPrice(
                    product,
                    website,
                    new BigDecimal(price),
                    productUrl,
                    LocalDateTime.now()
            ));
        }
    }
}