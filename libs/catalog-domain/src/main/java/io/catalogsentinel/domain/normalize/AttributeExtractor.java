package io.catalogsentinel.domain.normalize;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts {@link ProductAttributes} from structured attributes first and from the normalized title second.
 * Structured attributes win when both are present: suppliers' explicit fields are more reliable than text.
 */
public final class AttributeExtractor {

    private static final Pattern STORAGE_TOKEN = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)(gb|tb)\\b(?!\\s+(?:ram|memory|ddr\\d?))");
    private static final Pattern RAM_TOKEN = Pattern.compile("\\b(\\d+)gb\\s+(?:ram|memory|ddr\\d?)\\b");
    private static final Pattern INCH_TOKEN = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)in\\b");
    private static final Pattern GEN_TOKEN = Pattern.compile("\\b(?:gen|mk)(\\d+)\\b");
    private static final Pattern PACK_TOKEN = Pattern.compile("\\b(\\d+)pk\\b");
    private static final Pattern ML_TOKEN = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)ml\\b");
    private static final Pattern L_TOKEN = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)l\\b");
    private static final Pattern FLOZ_TOKEN = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)floz\\b");
    private static final Pattern QT_TOKEN = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)qt\\b");
    private static final Pattern GAL_TOKEN = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)gal\\b");
    private static final Pattern MAH_TOKEN = Pattern.compile("\\b(\\d+)mah\\b");
    private static final Pattern WATT_TOKEN = Pattern.compile("\\b(\\d+)w\\b");
    private static final Pattern VOLT_TOKEN = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)v\\b");
    private static final Pattern OUNCE_TOKEN = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)oz\\b");
    private static final Pattern APPAREL_SIZE =
            Pattern.compile("\\bsize\\s+(xxs|xs|s|m|l|xl|xxl|xxxl|small|medium|large|\\d{1,2}(?:\\.5)?)\\b");

    private AttributeExtractor() {
    }

    public static ProductAttributes extract(String normalizedTitle, Map<String, String> rawAttributes) {
        String title = normalizedTitle == null ? "" : normalizedTitle;
        Integer storage = null;
        Integer ram = null;
        String color = null;
        Double screen = null;
        String size = null;
        Integer generation = null;
        Integer pack = null;
        Integer volume = null;
        Integer mah = null;
        Integer watts = null;
        Double volts = null;
        Double ounces = null;

        if (rawAttributes != null) {
            for (Map.Entry<String, String> e : rawAttributes.entrySet()) {
                String key = e.getKey() == null ? "" : e.getKey().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
                String value = TextNormalizer.normalizeTitle(e.getValue());
                if (value == null) {
                    continue;
                }
                switch (key) {
                    case "color", "colour", "colorname", "colourname", "finish" -> color = ColorVocabulary.canonical(value);
                    case "storage", "storagecapacity", "capacity", "harddisksize", "internalstorage",
                         "memorystoragecapacity" -> {
                        Integer gb = parseStorageGb(value);
                        if (gb != null) {
                            storage = gb;
                        } else if (key.equals("capacity")) {
                            Integer ml = parseVolumeMl(value);
                            if (ml != null) {
                                volume = ml;
                            } else {
                                ounces = firstDouble(OUNCE_TOKEN, value);
                            }
                        }
                    }
                    case "ram", "memory", "ramsize", "systemmemory" -> ram = parseStorageGb(value);
                    case "screensize", "displaysize", "screen", "display" -> screen = parseInches(value);
                    case "size", "apparelsize", "shoesize", "clothingsize" -> size = canonicalSize(value);
                    case "generation", "gen" -> generation = parseFirstInt(value.replace("gen", ""));
                    case "packcount", "pack", "count", "quantity", "unitcount", "packsize" ->
                            pack = parseFirstInt(value.replace("pk", ""));
                    case "volume", "liquidvolume", "netvolume" -> volume = parseVolumeMl(value);
                    case "batterycapacity" -> mah = parseFirstInt(value.replace("mah", ""));
                    case "wattage", "power", "outputpower" -> watts = parseFirstInt(value.replace("w", ""));
                    case "voltage", "volt", "volts" -> volts = firstDouble(VOLT_TOKEN, value);
                    default -> {
                        // Unrecognized attributes still contribute to the generic attribute comparison.
                    }
                }
            }
        }

        if (ram == null) {
            ram = firstInt(RAM_TOKEN, title);
        }
        if (storage == null) {
            storage = largestStorageGb(title);
        }
        if (color == null) {
            color = ColorVocabulary.findIn(title);
        }
        if (screen == null) {
            // Inch values in titles are only screens when plausibly sized; "1/2-inch chuck" is not a 0.5" display.
            Double inches = firstDouble(INCH_TOKEN, title);
            if (inches != null && inches >= 3 && inches <= 120) {
                screen = inches;
            }
        }
        if (size == null) {
            Matcher m = APPAREL_SIZE.matcher(title);
            if (m.find()) {
                size = canonicalSize(m.group(1));
            }
        }
        if (generation == null) {
            generation = firstInt(GEN_TOKEN, title);
        }
        if (pack == null) {
            pack = firstInt(PACK_TOKEN, title);
        }
        if (volume == null) {
            volume = parseVolumeMl(title);
        }
        if (mah == null) {
            mah = firstInt(MAH_TOKEN, title);
        }
        if (watts == null) {
            watts = firstInt(WATT_TOKEN, title);
        }
        if (volts == null) {
            volts = firstDouble(VOLT_TOKEN, title);
        }
        if (ounces == null) {
            ounces = firstDouble(OUNCE_TOKEN, title);
        }
        return new ProductAttributes(storage, ram, color, screen, size, generation, pack, volume, mah, watts, volts,
                ounces);
    }

    static Integer parseStorageGb(String normalized) {
        Matcher m = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)(gb|tb)\\b").matcher(normalized);
        if (!m.find()) {
            return null;
        }
        double n = Double.parseDouble(m.group(1));
        return (int) Math.round(m.group(2).equals("tb") ? n * 1000 : n);
    }

    private static Integer largestStorageGb(String title) {
        Matcher m = STORAGE_TOKEN.matcher(title);
        Integer best = null;
        while (m.find()) {
            double n = Double.parseDouble(m.group(1));
            int gb = (int) Math.round(m.group(2).equals("tb") ? n * 1000 : n);
            if (best == null || gb > best) {
                best = gb;
            }
        }
        return best;
    }

    static Integer parseVolumeMl(String normalized) {
        Matcher ml = ML_TOKEN.matcher(normalized);
        if (ml.find()) {
            return (int) Math.round(Double.parseDouble(ml.group(1)));
        }
        Matcher l = L_TOKEN.matcher(normalized);
        if (l.find()) {
            return (int) Math.round(Double.parseDouble(l.group(1)) * 1000);
        }
        Matcher floz = FLOZ_TOKEN.matcher(normalized);
        if (floz.find()) {
            return (int) Math.round(Double.parseDouble(floz.group(1)) * 29.5735);
        }
        Matcher qt = QT_TOKEN.matcher(normalized);
        if (qt.find()) {
            return (int) Math.round(Double.parseDouble(qt.group(1)) * 946.353);
        }
        Matcher gal = GAL_TOKEN.matcher(normalized);
        if (gal.find()) {
            return (int) Math.round(Double.parseDouble(gal.group(1)) * 3785.41);
        }
        return null;
    }

    private static Double parseInches(String normalized) {
        Double d = firstDouble(INCH_TOKEN, normalized);
        if (d != null) {
            return d;
        }
        Matcher m = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\b").matcher(normalized);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    private static String canonicalSize(String value) {
        String v = value.strip();
        return switch (v) {
            case "small" -> "s";
            case "medium" -> "m";
            case "large" -> "l";
            case "extra large", "x large" -> "xl";
            default -> v.replace(" ", "");
        };
    }

    private static Integer parseFirstInt(String s) {
        Matcher m = Pattern.compile("(\\d+)").matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static Integer firstInt(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static Double firstDouble(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? Double.valueOf(m.group(1)) : null;
    }
}
