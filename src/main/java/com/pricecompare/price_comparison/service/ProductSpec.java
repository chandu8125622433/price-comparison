package com.pricecompare.price_comparison.service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Specifications extracted from a Vijay Sales product title.
 *
 * <p>Every field is {@link Optional} — absent when the title does
 * not contain a reliable value for that spec. No value is ever
 * fabricated.</p>
 */
public record ProductSpec(
        Optional<Integer> ram,
        Optional<Integer> storage,
        Optional<String> processor,
        Optional<Integer> screenSize,
        Optional<String> resolution,
        Optional<String> displayType,
        Optional<Integer> refreshRate,
        Optional<BigDecimal> tonnage,
        Optional<Integer> starRating,
        Optional<Boolean> inverter,
        Optional<String> acType,
        Optional<BigDecimal> capacity,
        Optional<String> doorType,
        Optional<String> loadType,
        Optional<String> cameraType,
        Optional<Integer> resolutionMP,
        Optional<String> headphoneType,
        Optional<Boolean> wireless,
        Optional<Boolean> anc,
        Optional<String> watchDisplay,
        Optional<Boolean> gps,
        Optional<Boolean> cellular,
        Optional<Boolean> fiveG
) {
    public static final ProductSpec EMPTY = new ProductSpec(
            Optional.<Integer>empty(), Optional.<Integer>empty(), Optional.<String>empty(), Optional.<Integer>empty(),
            Optional.<String>empty(), Optional.<String>empty(), Optional.<Integer>empty(), Optional.<BigDecimal>empty(),
            Optional.<Integer>empty(), Optional.<Boolean>empty(), Optional.<String>empty(), Optional.<BigDecimal>empty(),
            Optional.<String>empty(), Optional.<String>empty(), Optional.<String>empty(), Optional.<Integer>empty(),
            Optional.<String>empty(), Optional.<Boolean>empty(), Optional.<Boolean>empty(), Optional.<String>empty(),
            Optional.<Boolean>empty(), Optional.<Boolean>empty(), Optional.<Boolean>empty());
}
