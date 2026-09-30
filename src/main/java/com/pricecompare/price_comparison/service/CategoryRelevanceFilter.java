package com.pricecompare.price_comparison.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Determines whether a discovered candidate title is a genuine product within
 * a category — and NOT a warranty, protection plan, accessory, service, or
 * other non-product listing that happens to share the category keyword.
 *
 * <p>Primary validation uses {@link ProductSpecExtractor} to verify that
 * a product title contains category-defining specifications (e.g. screen size
 * for TVs, tonnage for ACs, RAM for mobiles). Products whose titles lack
 * such specifications are not genuine products of that category.</p>
 *
 * <p>A negative (blacklist) layer remains as defense-in-depth but is not the
 * primary mechanism — blacklists are added only for clearly identifiable
 * non-product patterns, not for keyword coverage.</p>
 */
@Component
public class CategoryRelevanceFilter {

    /** Terms that mark a listing as NOT a sellable product. */
    private static final List<Pattern> NON_PRODUCT_PATTERNS = List.of(
            Pattern.compile("\\bextended\\s+warranty\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwarranty\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bprotection\\s+plan\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("vs\\+\\s*ew", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\binsurance\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\binstallation\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bservice\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brepair\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\baccessories\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\baccessory\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcover\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcases\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcase\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcharger\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcable\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bscreen\\s+protector\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btempered\\s+glass\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bback\\s+cover\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcar\\s+holder\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bselfie\\s+stick\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpower\\s+bank\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpowerbank\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bstand\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bholder\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\badapter\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bremote\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmanual\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brefurbished\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bexchange\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bexchange\\s+offer\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpre.?owned\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgamepad\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcontroller\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bprinter\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bstylus\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btripod\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmemory\\s+card\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsd\\s+card\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpen\\s+drive\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpendrive\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bbag\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsleeve\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwall\\s*mount\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btv\\s+mount\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmount\\s+bracket\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bbracket\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bband\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bstrap\\b", Pattern.CASE_INSENSITIVE)
    );

    private static final Set<String> MOBILE_PRODUCT_BRANDS = Set.of(
            "samsung", "apple", "iphone", "xiaomi", "redmi", "oneplus", "oppo",
            "vivo", "realme", "nokia", "nothing", "pixel", "motorola", "asus",
            "honor", "poco", "iqoo", "google", "huawei", "tecno", "lava",
            "infinix", "itel", "micromax", "gionee", "karbonn", "lyf", "nubia",
            "blackberry", "razer", "rog"
    );

    /**
     * Returns true if {@code title} describes a genuine product in {@code category}.
     *
     * <p>Primary validation uses the retailer's source-side category
     * label when available: if the source explicitly classifies the
     * product under the target category it is accepted; if it classifies
     * the product under a different category it is rejected. This is the
     * strongest legitimate category signal available from the source.
     * When no source-side category is provided, validation falls back
     * to {@link ProductSpecExtractor} title-spec extraction.</p>
     */
    public boolean isRelevant(SearchClassifier.Category category, String title) {
        return isRelevant(category, title, "");
    }

    /**
     * Returns true if {@code title} describes a genuine product in {@code category},
     * using the retailer's source-side category label as the strongest validation signal.
     */
    public boolean isRelevant(SearchClassifier.Category category, String title,
                                   String sourceCategory) {
        if (title == null || title.isBlank()) {
            return false;
        }
        String lower = title.trim().toLowerCase(Locale.ROOT);

        // For CAMERA, check genuine camera before accessory patterns:
        // a real camera must never be rejected by bag/case/accessory keywords.
        if (category == SearchClassifier.Category.CAMERA && isCameraProduct(title, lower)) {
            return true;
        }

        // Defense-in-depth: reject known non-product patterns first.
        for (Pattern p : NON_PRODUCT_PATTERNS) {
            if (p.matcher(title).find()) {
                return false;
            }
        }

        // Strongest signal: retailer source-side category classification.
        if (sourceCategory != null && !sourceCategory.isBlank()) {
            SearchClassifier.Category sourceCat = normalizeSourceCategory(sourceCategory);
            if (sourceCat != null) {
                return sourceCat == category;
            }
        }

        // Primary: spec-based category validation.
        return switch (category) {
            case MOBILE -> isMobileProduct(title, lower);
            case LAPTOP -> isLaptopProduct(title, lower);
            case TV -> isTvProduct(title, lower);
            case AC -> isAcProduct(title, lower);
            case REFRIGERATOR -> isRefrigeratorProduct(title, lower);
            case WASHING_MACHINE -> isWashingMachineProduct(title, lower);
            case HEADPHONES -> isHeadphoneProduct(title, lower);
            case TABLET -> isTabletProduct(title, lower);
            case CAMERA -> isCameraProduct(title, lower);
            case SMARTWATCH -> isSmartwatchProduct(title, lower);
            default -> true;
        };
    }

    private static SearchClassifier.Category normalizeSourceCategory(String sourceCategory) {
        if (sourceCategory == null) return null;
        String upper = sourceCategory.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "MOBILE", "MOBILES", "SMARTPHONE", "SMARTPHONES", "CELL PHONES"
                    -> SearchClassifier.Category.MOBILE;
            case "LAPTOP", "LAPTOPS", "NOTEBOOK", "NOTEBOOKS"
                    -> SearchClassifier.Category.LAPTOP;
            case "TV", "TELEVISION", "TELEVISIONS", "TELEDISPLAY"
                    -> SearchClassifier.Category.TV;
            case "AC", "AIR CONDITIONER", "AIR CONDITIONERS"
                    -> SearchClassifier.Category.AC;
            case "REFRIGERATOR", "REFRIGERATORS", "FRIDGE", "FRIDGES"
                    -> SearchClassifier.Category.REFRIGERATOR;
            case "WASHING MACHINE", "WASHING MACHINES", "WASHER"
                    -> SearchClassifier.Category.WASHING_MACHINE;
            case "HEADPHONE", "HEADPHONES", "EARPHONE", "EARPHONES", "EARBUDS"
                    -> SearchClassifier.Category.HEADPHONES;
            case "TABLET", "TABLETS"
                    -> SearchClassifier.Category.TABLET;
            case "CAMERA", "CAMERAS"
                    -> SearchClassifier.Category.CAMERA;
            case "SMARTWATCH", "SMARTWATCHES", "SMART WATCH"
                    -> SearchClassifier.Category.SMARTWATCH;
            default -> null;
        };
    }

    private boolean isMobileProduct(String title, String lower) {
        ProductSpec spec = ProductSpecExtractor.fromTitle(title, SearchClassifier.Category.MOBILE);
        boolean hasFamily = lower.contains("phone") || lower.contains("smartphone")
                || lower.contains("galaxy") || lower.contains("iphone")
                || lower.contains("pixel") || lower.contains("redmi note");
        boolean hasBrand = MOBILE_PRODUCT_BRANDS.stream().anyMatch(lower::contains);
        boolean hasSpec = spec.ram().isPresent() || spec.storage().isPresent()
                || spec.processor().isPresent();
        return hasFamily || (hasBrand && hasSpec);
    }

    private boolean isLaptopProduct(String title, String lower) {
        ProductSpec spec = ProductSpecExtractor.fromTitle(title, SearchClassifier.Category.LAPTOP);
        boolean hasFamily = lower.contains("laptop") || lower.contains("notebook")
                || lower.contains("macbook") || lower.contains("chromebook")
                || lower.contains("vivobook") || lower.contains("ideapad")
                || lower.contains("thinkpad");
        boolean hasBrand = lower.contains("apple") || lower.contains("lenovo")
                || lower.contains("dell") || lower.contains("acer")
                || lower.contains("asus") || lower.contains("hp");
        boolean hasSpec = spec.ram().isPresent() || spec.storage().isPresent()
                || spec.processor().isPresent() || spec.screenSize().isPresent();
        return hasFamily || (hasBrand && hasSpec);
    }

    private boolean isTvProduct(String title, String lower) {
        ProductSpec spec = ProductSpecExtractor.fromTitle(title, SearchClassifier.Category.TV);
        boolean hasScreenSize = spec.screenSize().isPresent();
        boolean hasTech = spec.resolution().isPresent() || spec.displayType().isPresent()
                || spec.refreshRate().isPresent();
        // Spec-based: screen size + TV technology signal.
        if (hasScreenSize && hasTech) return true;
        // Keyword-based with strict accessory exclusion (defense-in-depth
        // already rejected wall mount, stand, etc.).
        return (lower.contains("tv") || lower.contains("television"))
                && !lower.contains("stabilizer") && !lower.contains("cable");
    }

    private boolean isAcProduct(String title, String lower) {
        ProductSpec spec = ProductSpecExtractor.fromTitle(title, SearchClassifier.Category.AC);
        boolean hasTonnage = spec.tonnage().isPresent();
        boolean hasType = spec.acType().isPresent();
        // Tonnage is unique to ACs among retail categories.
        if (hasTonnage) return true;
        // AC type + star rating or inverter is also a strong AC signal.
        return hasType && (spec.starRating().isPresent() || spec.inverter().isPresent());
    }

    private boolean isRefrigeratorProduct(String title, String lower) {
        ProductSpec spec = ProductSpecExtractor.fromTitle(title, SearchClassifier.Category.REFRIGERATOR);
        boolean hasCapacity = spec.capacity().isPresent();
        boolean hasFamily = lower.contains("refrigerator") || lower.contains("fridge");
        // Capacity in liters is unique to refrigerators.
        if (hasCapacity) return true;
        return hasFamily && (spec.doorType().isPresent() || spec.capacity().isPresent());
    }

    private boolean isWashingMachineProduct(String title, String lower) {
        ProductSpec spec = ProductSpecExtractor.fromTitle(title, SearchClassifier.Category.WASHING_MACHINE);
        boolean hasCapacity = spec.capacity().isPresent();
        boolean hasFamily = lower.contains("washing machine") || lower.contains("washer");
        // Capacity in kg is unique to washing machines.
        if (hasCapacity) return true;
        return hasFamily;
    }

    private boolean isHeadphoneProduct(String title, String lower) {
        ProductSpec spec = ProductSpecExtractor.fromTitle(title, SearchClassifier.Category.HEADPHONES);
        boolean hasType = spec.headphoneType().isPresent();
        boolean hasBrand = lower.contains("sony") || lower.contains("bose")
                || lower.contains("jbl") || lower.contains("boat")
                || lower.contains("sennheiser") || lower.contains("akg")
                || lower.contains("zebronics") || lower.contains("noise");
        // Headphone type (over-ear, TWS, earbud) is definitive.
        if (hasType) return true;
        // Brand + wireless is also a strong headphone signal.
        return hasBrand && spec.wireless().isPresent();
    }

    private static final Set<String> TABLET_FAMILY_KEYWORDS = Set.of(
            "galaxy tab", "ipad", "surface", "xiaomi pad", "lenovo tab",
            "nintendo switch", "kindle fire");
    private static final Set<String> CAMERA_BRAND_WORDS = Set.of(
            "sony", "canon", "nikon", "fujifilm", "kodak", "panasonic",
            "olympus", "pentax", "leica", "hasselblad", "ricoh");
    private static final Set<String> CAMERA_MODEL_WORDS = Set.of(
            "alpha", "eos", "x-t", "x-h", "x-s", "coolpix", "powershot",
            "lumix", "instax", "mini shot", "dslr", "mirrorless",
            "a7", "a9", "r5", "r6", "z6", "z7", "xt3", "xt4", "xt5");

    private boolean isTabletProduct(String title, String lower) {
        // Graphic/drawing tablets are a different category — reject them.
        if (lower.contains("xp-pen") || lower.contains("xp pen")
                || lower.contains("wacom") || lower.contains("huion")) {
            return false;
        }
        ProductSpec spec = ProductSpecExtractor.fromTitle(title, SearchClassifier.Category.TABLET);
        boolean hasSpec = spec.ram().isPresent() || spec.storage().isPresent()
                || spec.processor().isPresent() || spec.screenSize().isPresent();
        if (hasSpec) return true;
        if (TABLET_FAMILY_KEYWORDS.stream().anyMatch(lower::contains)) return true;
        boolean hasBrand = Set.of("samsung", "apple", "lenovo", "xiaomi",
                "microsoft", "nintendo").stream().anyMatch(lower::contains);
        return hasBrand && lower.contains("tab");
    }

    private boolean isCameraProduct(String title, String lower) {
        ProductSpec spec = ProductSpecExtractor.fromTitle(title, SearchClassifier.Category.CAMERA);
        boolean hasSpec = spec.cameraType().isPresent() && spec.resolutionMP().isPresent();
        if (hasSpec) return true;
        if (CAMERA_MODEL_WORDS.stream().anyMatch(lower::contains)) {
            return CAMERA_BRAND_WORDS.stream().anyMatch(lower::contains);
        }
        return false;
    }

    private boolean isSmartwatchProduct(String title, String lower) {
        ProductSpec spec = ProductSpecExtractor.fromTitle(title, SearchClassifier.Category.SMARTWATCH);
        return spec.gps().isPresent() || spec.cellular().isPresent()
                || spec.watchDisplay().isPresent();
    }
}
