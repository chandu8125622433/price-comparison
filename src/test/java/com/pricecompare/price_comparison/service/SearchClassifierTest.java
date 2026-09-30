package com.pricecompare.price_comparison.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchClassifierTest {

    private final SearchClassifier classifier = new SearchClassifier();

    @Test
    void tabletClassifiesAsCategory() {
        SearchClassifier.Classification c = classifier.classify("tablet");
        assertEquals(SearchClassifier.SearchType.CATEGORY, c.type());
        assertEquals(SearchClassifier.Category.TABLET, c.category());
    }

    @Test
    void tabletsClassifiesAsCategory() {
        SearchClassifier.Classification c = classifier.classify("tablets");
        assertEquals(SearchClassifier.SearchType.CATEGORY, c.type());
        assertEquals(SearchClassifier.Category.TABLET, c.category());
    }

    @Test
    void cameraClassifiesAsCategory() {
        SearchClassifier.Classification c = classifier.classify("camera");
        assertEquals(SearchClassifier.SearchType.CATEGORY, c.type());
        assertEquals(SearchClassifier.Category.CAMERA, c.category());
    }

    @Test
    void camerasClassifiesAsCategory() {
        SearchClassifier.Classification c = classifier.classify("cameras");
        assertEquals(SearchClassifier.SearchType.CATEGORY, c.type());
        assertEquals(SearchClassifier.Category.CAMERA, c.category());
    }

    @Test
    void smartwatchClassifiesAsCategory() {
        SearchClassifier.Classification c = classifier.classify("smartwatch");
        assertEquals(SearchClassifier.SearchType.CATEGORY, c.type());
        assertEquals(SearchClassifier.Category.SMARTWATCH, c.category());
    }

    @Test
    void smartwatchesClassifiesAsCategory() {
        SearchClassifier.Classification c = classifier.classify("smartwatches");
        assertEquals(SearchClassifier.SearchType.CATEGORY, c.type());
        assertEquals(SearchClassifier.Category.SMARTWATCH, c.category());
    }

    @Test
    void tvDiscoveryQueryIsTv() {
        SearchClassifier.Classification c = classifier.classify("TV");
        assertEquals(SearchClassifier.SearchType.CATEGORY, c.type());
        assertEquals("tv", c.canonicalQuery());
    }

    @Test
    void samsungTvCanonicalQueryContainsSamsungTv() {
        SearchClassifier.Classification c = classifier.classify("Samsung TV");
        assertEquals(SearchClassifier.SearchType.CATEGORY, c.type());
        assertTrue(c.canonicalQuery().contains("samsung tv"),
                "canonical query should contain 'samsung tv' but was: " + c.canonicalQuery());
    }
}
