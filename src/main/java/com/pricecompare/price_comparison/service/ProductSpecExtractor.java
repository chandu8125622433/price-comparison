package com.pricecompare.price_comparison.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts product specifications from Vijay Sales product titles.
 *
 * <p>Specifications are obtained from the product title text alone —
 * the legitimate source already available in the discovery flow
 * ({@link com.pricecompare.price_comparison.retailer.discovery.DiscoveredProduct#productTitle()}).
 * No data is fabricated: only patterns that reliably match text
 * in real Vijay Sales titles produce values.</p>
 */
public final class ProductSpecExtractor {

    private static final Pattern RAM_GB_SLASH =
            Pattern.compile("(\\d+)\\s*GB\\s*/\\s*(\\d+)\\s*(GB|TB)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAM_GB_WORD =
            Pattern.compile("(\\d+)\\s*GB\\s*RAM", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAM_GB =
            Pattern.compile("(\\d+)\\s*GB\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STORAGE_GB =
            Pattern.compile("(\\d+)\\s*(GB|TB)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROCESSOR =
            Pattern.compile("\\b(Snapdragon\\s*\\d+[A-Za-z]*|MediaTek\\s*\\d+[A-Za-z]*|"
                    + "Dimensity\\s*\\d+[A-Za-z]*|Exynos\\s*\\d+[A-Za-z]*|"
                    + "Core\\s*i[3579]|Ryzen\\s*\\d+|M\\d+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SCREEN_INCH =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:inch|\"|in)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCREEN_CM =
            Pattern.compile("(\\d+)\\s*cm", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOLUTION =
            Pattern.compile("\\b(4K|8K|Full HD|FHD|HD|UHD|QD-OLED)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern DISPLAY_TYPE =
            Pattern.compile("\\b(LED|OLED|QLED|Crystal)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern REFRESH_RATE =
            Pattern.compile("(\\d+)\\s*Hz", Pattern.CASE_INSENSITIVE);
    private static final Pattern TONNAGE =
            Pattern.compile("(\\d\\.\\d|\\d)\\s*Ton", Pattern.CASE_INSENSITIVE);
    private static final Pattern STAR_RATING =
            Pattern.compile("(\\d)\\s*Star", Pattern.CASE_INSENSITIVE);
    private static final Pattern AC_TYPE =
            Pattern.compile("(Split AC|Window AC|Portable AC|Cassette AC)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPACITY_L =
            Pattern.compile("(\\d{2,4})\\s*L\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPACITY_KG =
            Pattern.compile("(\\d(?:\\.\\d+)?)\\s*Kg", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOOR_TYPE =
            Pattern.compile("(Double Door|Single Door|Side by Side|Frost Free)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern LOAD_TYPE =
            Pattern.compile("(Front Load|Top Load|Semi Automatic|Fully Automatic)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern CAMERA_TYPE =
            Pattern.compile("\\b(DSLR|Mirrorless|Compact|Action Cam)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern MP =
            Pattern.compile("(\\d+)\\s*MP\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADPHONE_TYPE =
            Pattern.compile("\\b(Over-ear|On-ear|In-ear|TWS|Earbud|Neckband)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern WATCH_DISPLAY =
            Pattern.compile("\\b(Retina|AMOLED|LCD|OLED)\\b",
                    Pattern.CASE_INSENSITIVE);

    private ProductSpecExtractor() {}

    public static ProductSpec fromTitle(String title, SearchClassifier.Category category) {
        if (title == null || title.isBlank()) {
            return ProductSpec.EMPTY;
        }
        String lower = title.toLowerCase(Locale.ROOT);
        return switch (category) {
            case MOBILE -> extractMobile(title, lower);
            case LAPTOP -> extractLaptop(title, lower);
            case TABLET -> extractTablet(title, lower);
            case TV -> extractTv(title, lower);
            case AC -> extractAc(title, lower);
            case REFRIGERATOR -> extractRefrigerator(title, lower);
            case WASHING_MACHINE -> extractWashingMachine(title, lower);
            case CAMERA -> extractCamera(title, lower);
            case HEADPHONES -> extractHeadphones(title, lower);
            case SMARTWATCH -> extractSmartwatch(title, lower);
            default -> ProductSpec.EMPTY;
        };
    }

    private static ProductSpec extractMobile(String title, String lower) {
        Optional<Integer> ram = extractRam(title);
        Optional<Integer> storage = extractStorage(title);
        return new ProductSpec(
                ram, storage, findProcessor(title), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), isWireless(title, lower),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), lower.contains("5g") ? Optional.of(true) : Optional.empty());
    }

    private static ProductSpec extractLaptop(String title, String lower) {
        Optional<Integer> ram = extractRam(title);
        Optional<Integer> storage = extractStorage(title);
        return new ProductSpec(
                ram, storage, findProcessor(title), extractScreenSize(title, lower),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), isWireless(title, lower),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static ProductSpec extractTablet(String title, String lower) {
        Optional<Integer> ram = extractRam(title);
        Optional<Integer> storage = extractStorage(title);
        return new ProductSpec(
                ram, storage, findProcessor(title), extractScreenSize(title, lower),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), isWireless(title, lower),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static ProductSpec extractTv(String title, String lower) {
        return new ProductSpec(
                Optional.empty(), Optional.empty(), Optional.empty(),
                extractScreenSize(title, lower), findResolution(title),
                findDisplayType(title), findRefreshRate(title),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static ProductSpec extractAc(String title, String lower) {
        return new ProductSpec(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), findTonnage(title), findStarRating(title),
                isInverter(title, lower), findAcType(title),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ProductSpec extractRefrigerator(String title, String lower) {
        return new ProductSpec(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), findCapacityL(title),
                findDoorType(title), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static ProductSpec extractWashingMachine(String title, String lower) {
        return new ProductSpec(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), findBigDecimal(CAPACITY_KG, title),
                Optional.empty(), findLoadType(title), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static ProductSpec extractCamera(String title, String lower) {
        return new ProductSpec(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), findCameraType(title),
                findMp(title), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static ProductSpec extractHeadphones(String title, String lower) {
        return new ProductSpec(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), findHeadphoneType(title),
                isWireless(title, lower), findAnc(title, lower),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static ProductSpec extractSmartwatch(String title, String lower) {
        return new ProductSpec(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), findWatchDisplay(title),
                lower.contains("gps") ? Optional.of(true) : Optional.empty(),
                (lower.contains("cellular") || lower.contains("lte"))
                        ? Optional.of(true) : Optional.empty(),
                Optional.empty());
    }

    private static Optional<Integer> extractRam(String title) {
        Matcher m = RAM_GB_SLASH.matcher(title);
        if (m.find()) {
            return Optional.of(Integer.parseInt(m.group(1)));
        }
        m = RAM_GB_WORD.matcher(title);
        if (m.find()) {
            return Optional.of(Integer.parseInt(m.group(1)));
        }
        List<Integer> gbValues = findAllGbValues(title);
        if (gbValues.size() >= 2) {
            return Optional.of(Collections.min(gbValues));
        }
        return Optional.empty();
    }

    private static Optional<Integer> extractStorage(String title) {
        List<Integer> gbValues = findAllGbValues(title);
        if (gbValues.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Collections.max(gbValues));
    }

    private static List<Integer> findAllGbValues(String title) {
        Matcher m = STORAGE_GB.matcher(title);
        List<Integer> values = new ArrayList<>();
        while (m.find()) {
            values.add(Integer.parseInt(m.group(1)));
        }
        return values;
    }

    private static Optional<Integer> extractStorageAll(String title) {
        Matcher m = STORAGE_GB.matcher(title);
        if (m.find()) {
            return Optional.of(Integer.parseInt(m.group(1)));
        }
        return Optional.empty();
    }

    private static Optional<Integer> excludeValue(Optional<Integer> storage, int exclude) {
        return storage.filter(v -> v != exclude);
    }

    private static Optional<String> findProcessor(String title) {
        Matcher m = PROCESSOR.matcher(title);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private static Optional<Integer> extractScreenSize(String title, String lower) {
        Matcher m = SCREEN_INCH.matcher(title);
        if (m.find()) {
            return Optional.of((int) Math.round(Double.parseDouble(m.group(1))));
        }
        m = SCREEN_CM.matcher(title);
        if (m.find()) {
            return Optional.of((int) Math.round(Double.parseDouble(m.group(1)) * 0.393701));
        }
        return Optional.empty();
    }

    private static Optional<String> findResolution(String title) {
        return findFirst(RESOLUTION, title);
    }

    private static Optional<String> findDisplayType(String title) {
        return findFirst(DISPLAY_TYPE, title);
    }

    private static Optional<Integer> findRefreshRate(String title) {
        return findInt(REFRESH_RATE, title);
    }

    private static Optional<BigDecimal> findTonnage(String title) {
        return findBigDecimal(TONNAGE, title);
    }

    private static Optional<Integer> findStarRating(String title) {
        return findInt(STAR_RATING, title);
    }

    private static Optional<String> findAcType(String title) {
        return findFirst(AC_TYPE, title);
    }

    private static Optional<BigDecimal> findCapacityL(String title) {
        return findBigDecimal(CAPACITY_L, title);
    }

    private static Optional<String> findDoorType(String title) {
        return findFirst(DOOR_TYPE, title);
    }

    private static Optional<String> findLoadType(String title) {
        return findFirst(LOAD_TYPE, title);
    }

    private static Optional<String> findCameraType(String title) {
        return findFirst(CAMERA_TYPE, title);
    }

    private static Optional<Integer> findMp(String title) {
        return findInt(MP, title);
    }

    private static Optional<String> findHeadphoneType(String title) {
        return findFirst(HEADPHONE_TYPE, title);
    }

    private static Optional<String> findWatchDisplay(String title) {
        return findFirst(WATCH_DISPLAY, title);
    }

    private static Optional<Boolean> isInverter(String title, String lower) {
        return lower.contains("inverter") ? Optional.of(true) : Optional.empty();
    }

    private static Optional<Boolean> isWireless(String title, String lower) {
        return (lower.contains("wireless") || lower.contains("bluetooth"))
                ? Optional.of(true) : Optional.empty();
    }

    private static Optional<Boolean> findAnc(String title, String lower) {
        return (lower.contains("anc") || lower.contains("active noise"))
                ? Optional.of(true) : Optional.empty();
    }

    private static <T> Optional<T> findFirst(Pattern pattern, String title) {
        Matcher m = pattern.matcher(title);
        if (m.find()) {
            return Optional.of((T) m.group(1));
        }
        return Optional.empty();
    }

    private static Optional<Integer> findInt(Pattern pattern, String title) {
        Matcher m = pattern.matcher(title);
        if (m.find()) {
            try {
                return Optional.of(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<BigDecimal> findBigDecimal(Pattern pattern, String title) {
        Matcher m = pattern.matcher(title);
        if (m.find()) {
            try {
                return Optional.of(new BigDecimal(m.group(1)));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
