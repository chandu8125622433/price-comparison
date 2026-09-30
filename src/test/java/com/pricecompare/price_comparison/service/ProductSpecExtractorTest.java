package com.pricecompare.price_comparison.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductSpecExtractorTest {

    @Test
    void emptyTitleReturnsEmptySpec() {
        assertEquals(ProductSpec.EMPTY, ProductSpecExtractor.fromTitle(null, SearchClassifier.Category.MOBILE));
        assertEquals(ProductSpec.EMPTY, ProductSpecExtractor.fromTitle("", SearchClassifier.Category.MOBILE));
        assertEquals(ProductSpec.EMPTY, ProductSpecExtractor.fromTitle("   ", SearchClassifier.Category.MOBILE));
    }

    @Test
    void unknownCategoryReturnsEmptySpec() {
        assertEquals(ProductSpec.EMPTY, ProductSpecExtractor.fromTitle("Samsung 55 inch 4K Smart TV",
                SearchClassifier.Category.UNKNOWN));
    }

    /* --- MOBILE --- */

    @Test
    void mobileExtractsRamAndStorageFromSlashFormat() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Samsung Galaxy M36 8GB/256GB",
                SearchClassifier.Category.MOBILE);
        assertEquals(Optional.of(8), spec.ram());
        assertEquals(Optional.of(256), spec.storage());
    }

    @Test
    void mobileExtractsRamFromWordFormat() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("OnePlus N6 5G 128 GB, 4 GB RAM, Smartphone",
                SearchClassifier.Category.MOBILE);
        assertEquals(Optional.of(4), spec.ram());
    }

    @Test
    void mobileExtractsStorageWhenRamExtracted() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Samsung Galaxy M36 8GB/256GB",
                SearchClassifier.Category.MOBILE);
        assertEquals(Optional.of(256), spec.storage());
    }

    @Test
    void mobileDetectsFiveG() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Samsung Galaxy M36 5G",
                SearchClassifier.Category.MOBILE);
        assertEquals(Optional.of(true), spec.fiveG());
    }

    @Test
    void mobileNoFiveGWhenNotInTitle() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Samsung Galaxy S25",
                SearchClassifier.Category.MOBILE);
        assertFalse(spec.fiveG().isPresent());
    }

    @Test
    void mobileExtractsProcessor() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Xiaomi Redmi Note 13 Pro Snapdragon 7s Gen 3",
                SearchClassifier.Category.MOBILE);
        assertTrue(spec.processor().isPresent());
        assertTrue(spec.processor().get().toLowerCase().contains("snapdragon"));
    }

    @Test
    void mobileMissingSpecsNotFabricated() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Samsung Galaxy S25",
                SearchClassifier.Category.MOBILE);
        assertFalse(spec.ram().isPresent());
        assertFalse(spec.storage().isPresent());
        assertFalse(spec.processor().isPresent());
        assertFalse(spec.screenSize().isPresent());
        assertFalse(spec.resolution().isPresent());
    }

    /* --- LAPTOP --- */

    @Test
    void laptopExtractsRamStorageProcessor() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Apple MacBook Air M3 8GB 256GB",
                SearchClassifier.Category.LAPTOP);
        assertEquals(Optional.of(8), spec.ram());
        assertEquals(Optional.of(256), spec.storage());
        assertTrue(spec.processor().isPresent());
    }

    @Test
    void laptopExtractsScreenSize() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Lenovo ThinkPad X1 Carbon 14 inch",
                SearchClassifier.Category.LAPTOP);
        assertEquals(Optional.of(14), spec.screenSize());
    }

    @Test
    void laptopNoScreenSizeWhenNotInTitle() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Apple MacBook Air M3",
                SearchClassifier.Category.LAPTOP);
        assertFalse(spec.screenSize().isPresent());
    }

    /* --- TV --- */

    @Test
    void tvExtractsScreenSizeResolutionDisplayType() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Samsung 55 inch 4K Smart TV",
                SearchClassifier.Category.TV);
        assertEquals(Optional.of(55), spec.screenSize());
        assertEquals(Optional.of("4K"), spec.resolution());
    }

    @Test
    void tvExtractsDisplayType() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("LG OLED Smart TV",
                SearchClassifier.Category.TV);
        assertEquals(Optional.of("OLED"), spec.displayType());
    }

    @Test
    void tvExtractsCmvScreenSize() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("TCL 80 cm (32 inch) Full HD TV",
                SearchClassifier.Category.TV);
        assertEquals(Optional.of(32), spec.screenSize());
        assertEquals(Optional.of("Full HD"), spec.resolution());
    }

    @Test
    void tvNoResolutionWhenNotInTitle() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Samsung Smart TV",
                SearchClassifier.Category.TV);
        assertFalse(spec.resolution().isPresent());
    }

    /* --- AC --- */

    @Test
    void acExtractsTonnageStarRatingTypeInverter() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("LG 1.5 Ton 5 Star Inverter Split AC",
                SearchClassifier.Category.AC);
        assertEquals(Optional.of(new java.math.BigDecimal("1.5")), spec.tonnage());
        assertEquals(Optional.of(5), spec.starRating());
        assertEquals(Optional.of("Split AC"), spec.acType());
        assertEquals(Optional.of(true), spec.inverter());
    }

    @Test
    void acInverterNotDetectedWhenNotInTitle() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("LG 1.5 Ton 5 Star Window AC",
                SearchClassifier.Category.AC);
        assertFalse(spec.inverter().isPresent());
    }

    @Test
    void acExtractsWindowType() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Samsung 1.5 Ton 3 Star Window AC",
                SearchClassifier.Category.AC);
        assertEquals(Optional.of("Window AC"), spec.acType());
    }

    /* --- REFRIGERATOR --- */

    @Test
    void refrigeratorExtractsCapacityDoorType() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Samsung 253L Double Door Refrigerator",
                SearchClassifier.Category.REFRIGERATOR);
        assertEquals(Optional.of(new java.math.BigDecimal("253")), spec.capacity());
        assertEquals(Optional.of("Double Door"), spec.doorType());
    }

    @Test
    void refrigeratorNoDoorTypeWhenNotInTitle() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Samsung 253L Refrigerator",
                SearchClassifier.Category.REFRIGERATOR);
        assertFalse(spec.doorType().isPresent());
    }

    /* --- WASHING MACHINE --- */

    @Test
    void washingMachineExtractsCapacityLoadType() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("LG 8 Kg Front Load Washing Machine",
                SearchClassifier.Category.WASHING_MACHINE);
        assertEquals(Optional.of(new java.math.BigDecimal("8")), spec.capacity());
        assertEquals(Optional.of("Front Load"), spec.loadType());
    }

    /* --- CAMERA --- */

    @Test
    void cameraExtractsTypeAndResolution() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Sony Alpha 7 IV DSLR 45MP",
                SearchClassifier.Category.CAMERA);
        assertEquals(Optional.of("DSLR"), spec.cameraType());
        assertEquals(Optional.of(45), spec.resolutionMP());
    }

    /* --- HEADPHONES --- */

    @Test
    void headphonesExtractTypeWireless() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Sony WH-1000XM5 Over-ear Wireless",
                SearchClassifier.Category.HEADPHONES);
        assertEquals(Optional.of("Over-ear"), spec.headphoneType());
        assertEquals(Optional.of(true), spec.wireless());
    }

    @Test
    void headphonesDetectAnc() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Sony WH-1000XM5 ANC Wireless",
                SearchClassifier.Category.HEADPHONES);
        assertEquals(Optional.of(true), spec.anc());
    }

    /* --- SMARTWATCH --- */

    @Test
    void smartwatchExtractsGps() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Apple Watch SE GPS 40mm",
                SearchClassifier.Category.SMARTWATCH);
        assertEquals(Optional.of(true), spec.gps());
    }

    @Test
    void smartwatchDetectsCellular() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Apple Watch Cellular 40mm",
                SearchClassifier.Category.SMARTWATCH);
        assertEquals(Optional.of(true), spec.cellular());
    }

    /* --- No fabrication: absent specs remain absent --- */

    @Test
    void simpleTitleProducesOnlyExtractableSpecs() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Samsung Galaxy S25",
                SearchClassifier.Category.MOBILE);
        assertEquals(ProductSpec.EMPTY, spec);
    }

    @Test
    void tvWithOnlySizeProducesNoFabricatedSpecs() {
        ProductSpec spec = ProductSpecExtractor.fromTitle("Samsung 55 inch TV",
                SearchClassifier.Category.TV);
        assertEquals(Optional.of(55), spec.screenSize());
        assertFalse(spec.resolution().isPresent());
        assertFalse(spec.displayType().isPresent());
        assertFalse(spec.refreshRate().isPresent());
    }
}
