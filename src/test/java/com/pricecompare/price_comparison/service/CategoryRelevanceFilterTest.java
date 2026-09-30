package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.service.SearchClassifier.Category;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Category relevance rules (PriceDekho-style category listing): a category
 * query must surface genuine products of that category and NEVER services,
 * warranties, or accessories that merely mention the category keyword.
 */
class CategoryRelevanceFilterTest {

    private final CategoryRelevanceFilter filter = new CategoryRelevanceFilter();

    /* THE original bug: warranty plan ranked as a "mobile". */
    @Test
    void warrantyPlanIsNeverAMobileProduct() {
        assertFalse(filter.isRelevant(Category.MOBILE,
                "1 Year VS Plus Extended Warranty for Mobiles From Rs.90001 To Rs.100000"));
        assertFalse(filter.isRelevant(Category.MOBILE,
                "Mobile Protection Plan by retailer"));
    }

    /* Genuine mobiles pass. */
    @Test
    void genuineMobilesAreAccepted() {
        assertTrue(filter.isRelevant(Category.MOBILE, "Samsung Galaxy S25"));
        assertTrue(filter.isRelevant(Category.MOBILE,
                "OnePlus N6 5G 128 GB, 4 GB RAM, Smartphone, Midnight Green"));
        assertTrue(filter.isRelevant(Category.MOBILE,
                "JioPhone Next 32 GB, 2 GB RAM, Black Mobile Phone, LS1542QWN"));
    }

    /* Accessories that embed the keyword "smartphone"/"mobile" are rejected. */
    @Test
    void smartphoneAdjacentAccessoriesAreRejected() {
        assertFalse(filter.isRelevant(Category.MOBILE,
                "Evofox Deck 2 Smartphone Gamepad Gaming Controller, White"));
        assertFalse(filter.isRelevant(Category.MOBILE,
                "Fujifilm instax Mini Link 2 Smartphone Printer with Wireless Printing"));
        assertFalse(filter.isRelevant(Category.MOBILE,
                "65W Fast Charger with Type-C Cable"));
        assertFalse(filter.isRelevant(Category.MOBILE,
                "Silicone Back Cover for Samsung Galaxy S25"));
        assertFalse(filter.isRelevant(Category.MOBILE,
                "SanDisk 128 GB Memory Card for Mobiles"));
        assertFalse(filter.isRelevant(Category.MOBILE,
                "Tempered Glass Screen Protector for iPhone 16"));
    }

    /* Other categories still work with their own positive signals. */
    @Test
    void otherCategoriesAcceptGenuineProducts() {
        assertTrue(filter.isRelevant(Category.AC,
                "LG 1.5 Ton 5 Star Inverter Split AC (AS-Q20ENZE)"));
        assertTrue(filter.isRelevant(Category.LAPTOP, "Apple MacBook Air M3"));
        assertTrue(filter.isRelevant(Category.TV,
                "Samsung 55 inch 4K Smart TV Crystal UHD"));
        assertTrue(filter.isRelevant(Category.HEADPHONES,
                "Sony WH-1000XM5 Wireless Headphones"));
    }

    /* Phase 8B: "1 Yr VS+ EW" warranty format rejected for TV. */
    @Test
    void vsPlusEwWarrantyTvProductsAreRejected() {
        assertFalse(filter.isRelevant(Category.TV, "1 Yr VS+ EW for Televisions - 5K"));
        assertFalse(filter.isRelevant(Category.TV, "1 Yr VS+ EW for Televisions - 10K"));
        assertFalse(filter.isRelevant(Category.TV, "1 Year VS+ EW for Televisions - 50K"));
    }

    /* Phase 8B: TV accessories are rejected even if they contain "tv". */
    @Test
    void tvAccessoriesAreRejected() {
        assertFalse(filter.isRelevant(Category.TV, "Samsung TV Wall Mount"));
        assertFalse(filter.isRelevant(Category.TV, "TV Wall Mount Bracket"));
        assertFalse(filter.isRelevant(Category.TV, "Samsung TV Wallmount"));
        assertFalse(filter.isRelevant(Category.TV, "TV Wallmount Stand"));
    }

    /* Phase 8B: Genuine TV products are still accepted. */
    @Test
    void genuineTvProductsAreAccepted() {
        assertTrue(filter.isRelevant(Category.TV, "Samsung 55 inch 4K Smart TV"));
        assertTrue(filter.isRelevant(Category.TV, "LG OLED Smart TV"));
    }

    /* Cross-category noise is rejected by the positive layer. */
    @Test
    void crossCategoryTitlesAreRejected() {
        assertFalse(filter.isRelevant(Category.MOBILE,
                "Samsung 253L Double Door Refrigerator RT28C3723S8"));
        assertFalse(filter.isRelevant(Category.MOBILE,
                "LG 8 Kg Front Load Washing Machine FHM1408SDL"));
    }

    /* TV wrong-category examples are rejected. */
    @Test
    void tvWrongCategoryExamplesAreRejected() {
        assertFalse(filter.isRelevant(Category.TV, "V-Guard Crystal TT 150 Plus TV Stabilizer"));
        assertFalse(filter.isRelevant(Category.TV, "Samsung TV Wall Mount"));
        assertFalse(filter.isRelevant(Category.TV, "HDMI Hub 4K Splitter"));
        assertFalse(filter.isRelevant(Category.TV, "Ethernet Cat6 Cable 10m"));
        assertFalse(filter.isRelevant(Category.TV, "Samsung Soundbar HW-Q60R"));
        assertFalse(filter.isRelevant(Category.TV, "Gaming Controller for TV"));
    }

    /* Laptop wrong-category examples are rejected. */
    @Test
    void laptopWrongCategoryExamplesAreRejected() {
        assertFalse(filter.isRelevant(Category.LAPTOP, "Muvtech Ruvo Laptop Bag for Laptops Up to 14 inches"));
        assertFalse(filter.isRelevant(Category.LAPTOP, "Laptop Sleeve 15.6 inch"));
    }

    /* AC wrong-category examples are rejected. */
    @Test
    void acWrongCategoryExamplesAreRejected() {
        assertFalse(filter.isRelevant(Category.AC,
                "V-Guard Arizor 4170 12 Ampere Voltage Stabilizer for Inverter AC"));
        assertFalse(filter.isRelevant(Category.AC, "AC Stand and Accessories Kit"));
    }

    /* Genuine consumer tablets are accepted; graphic tablets are rejected. */
    @Test
    void genuineTabletsAreAccepted() {
        assertTrue(filter.isRelevant(Category.TABLET, "Samsung Galaxy Tab S9"));
        assertTrue(filter.isRelevant(Category.TABLET, "Samsung Galaxy Tab S9 Plus 5G"));
        assertTrue(filter.isRelevant(Category.TABLET, "Apple iPad Air"));
        assertTrue(filter.isRelevant(Category.TABLET, "Lenovo Tab M10"));
        assertTrue(filter.isRelevant(Category.TABLET, "Xiaomi Pad 6"));
    }

    /* Graphic/drawing tablets must NOT appear in normal TABLET results. */
    @Test
    void graphicTabletsAreRejected() {
        assertFalse(filter.isRelevant(Category.TABLET, "XP-PEN Star G960S Plus Graphic Tablet 9x6 inch"));
        assertFalse(filter.isRelevant(Category.TABLET, "Wacom Intuos Pro Graphic Tablet"));
        assertFalse(filter.isRelevant(Category.TABLET, "Huion Kamvas 13 Drawing Tablet"));
        assertFalse(filter.isRelevant(Category.TABLET, "XP-PEN Deco Mini4W"));
        assertFalse(filter.isRelevant(Category.TABLET, "Wacom Cintiq 16"));
        assertFalse(filter.isRelevant(Category.TABLET, "Huion H610X Pro"));
    }

    /* Genuine cameras are accepted. */
    @Test
    void genuineCamerasAreAccepted() {
        assertTrue(filter.isRelevant(Category.CAMERA, "Sony Alpha 7 IV Camera"));
        assertTrue(filter.isRelevant(Category.CAMERA, "Canon EOS R5"));
        assertTrue(filter.isRelevant(Category.CAMERA, "Kodak Mini Shot 4 Era Instant Camera"));
        assertTrue(filter.isRelevant(Category.CAMERA, "Nikon Z6 III"));
        assertTrue(filter.isRelevant(Category.CAMERA, "Fujifilm X-T5"));
    }

    /* Camera accessories are rejected even though they contain camera keyword. */
    @Test
    void cameraAccessoriesAreRejected() {
        assertFalse(filter.isRelevant(Category.CAMERA, "Camera Bag"));
        assertFalse(filter.isRelevant(Category.CAMERA, "Camera Case"));
        assertFalse(filter.isRelevant(Category.CAMERA, "Camera Cleaning Kit"));
        assertFalse(filter.isRelevant(Category.CAMERA, "Camera Accessory Kit"));
        assertFalse(filter.isRelevant(Category.CAMERA, "Camera Tripod"));
        assertFalse(filter.isRelevant(Category.CAMERA, "Camera Memory Card"));
    }
}