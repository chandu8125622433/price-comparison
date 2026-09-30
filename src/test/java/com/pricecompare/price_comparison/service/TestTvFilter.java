package com.pricecompare.price_comparison.service;

public class TestTvFilter {
    public static void main(String[] args) {
        CategoryRelevanceFilter filter = new CategoryRelevanceFilter();
        String[] tvTests = {
            "TP-Link EH210 1-to-2 Gigabit Ethernet Splitter",
            "TV Wallmount",
            "V-Guard Crystal TT 150 Plus TV Stabilizer",
            "Samsung 55 inch 4K Smart TV",
            "LG OLED Smart TV",
            "Panasonic 75-inch 4K Ultra HD Smart Google TV",
            "HDMI Splitter 4K",
            "Ethernet Cat6 Cable 10m",
        };
        for (String t : tvTests) {
            boolean relevant = filter.isRelevant(SearchClassifier.Category.TV, t);
            System.out.println("TV: " + t + " => " + relevant);
        }
    }
}
