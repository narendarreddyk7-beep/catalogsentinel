package io.catalogsentinel.domain.normalize;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic text normalization shared by ingestion (persisted normalized columns), candidate
 * retrieval and matching.
 *
 * <p>The pipeline is ordered so that unit expressions are canonicalized <em>before</em> punctuation is
 * stripped: {@code 55"} only means "55 inches" while the quote is still there. Meaningful variant
 * information (storage, generation, size, pack count) is rewritten into compact tokens such as
 * {@code 256gb}, {@code gen2}, {@code 55in} and {@code 2pk} rather than discarded, so later stages can
 * detect variant conflicts.
 */
public final class TextNormalizer {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM_KEEP_DOT = Pattern.compile("[^a-z0-9.\\s]");
    private static final Pattern STRAY_DOT = Pattern.compile("(?<!\\d)\\.|\\.(?!\\d)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private static final Pattern DOUBLE_A = Pattern.compile("\\bdouble\\s+a\\b");
    private static final Pattern TRIPLE_A = Pattern.compile("\\btriple\\s+a\\b");
    private static final Pattern FRACTION = Pattern.compile("\\b(\\d{1,2})/(2|3|4|8|16)\\b");
    private static final Pattern QUART = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*(?:quarts?|qts?)\\b");
    private static final Pattern GALLON = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*(?:gallons?|gal)\\b");
    private static final Pattern VOLT = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*(?:volts?|v)\\b");
    private static final Pattern N_IN_M = Pattern.compile("\\b(\\d)\\s*-?\\s*in\\s*-?\\s*(\\d)\\b");
    private static final Pattern INCH_MARK = Pattern.compile("(\\d)\\s*(?:''|\")");
    private static final Pattern INCH_WORD =
            Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*(?:inches|inch|in)\\b");
    private static final Pattern TB = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*(?:terabytes?|tb)\\b");
    private static final Pattern GB = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*(?:gigabytes?|gigs?|gb)\\b");
    private static final Pattern MB = Pattern.compile("\\b(\\d+)\\s*-?\\s*(?:megabytes?|mb)\\b");
    private static final Pattern MAH = Pattern.compile("\\b(\\d+)\\s*-?\\s*mah\\b");
    private static final Pattern WATT = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*(?:watts?|w)\\b");
    private static final Pattern HERTZ = Pattern.compile("\\b(\\d+)\\s*-?\\s*(?:hertz|hz)\\b");
    private static final Pattern FLUID_OZ =
            Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*(?:fl\\.?\\s*oz\\.?|fluid\\s+ounces?)");
    private static final Pattern OUNCE = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*(?:ounces?|oz)\\b");
    private static final Pattern MILLILITER =
            Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*(?:milliliters?|millilitres?|ml)\\b");
    private static final Pattern LITER = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*(?:liters?|litres?|ltr|l)\\b");
    private static final Pattern POUND = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*(?:pounds?|lbs?)\\b");
    private static final Pattern KILOGRAM = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*(?:kilograms?|kilos?|kg)\\b");
    private static final Pattern MILLIMETER = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*mm\\b");
    private static final Pattern CENTIMETER = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*-?\\s*cm\\b");
    private static final Pattern PACK_OF = Pattern.compile("\\b(?:pack|set|box|case)\\s+of\\s+(\\d+)\\b");
    private static final Pattern N_PACK =
            Pattern.compile("\\b(\\d+)\\s*-?\\s*(?:packs?|pk|count|ct)\\b");
    private static final Pattern ORDINAL_GEN =
            Pattern.compile("\\b(\\d+)(?:st|nd|rd|th)\\s*-?\\s*gen(?:eration)?\\b");
    private static final Pattern GEN_NUMBER = Pattern.compile("\\bgen(?:eration)?\\s*-?\\s*(\\d+)\\b");
    private static final Pattern WORD_GEN =
            Pattern.compile("\\b(first|second|third|fourth|fifth|sixth|seventh|eighth)\\s+gen(?:eration)?\\b");
    private static final Pattern MARK = Pattern.compile("\\b(?:mark|mk)\\s*-?\\s*(iii|ii|iv|v|vi|[2-9])\\b");
    private static final Pattern HYPHEN_JOIN = Pattern.compile("(?<=[a-z])-(?=[a-z0-9])|(?<=[0-9])-(?=[a-z])");

    private static final Map<String, String> ORDINAL_WORDS = Map.of(
            "first", "1", "second", "2", "third", "3", "fourth", "4",
            "fifth", "5", "sixth", "6", "seventh", "7", "eighth", "8");
    private static final Map<String, String> ROMAN = Map.of(
            "ii", "2", "iii", "3", "iv", "4", "v", "5", "vi", "6");
    private static final Map<String, String> TOKEN_SYNONYMS = Map.of(
            "grey", "gray",
            "colour", "color",
            "aluminium", "aluminum",
            "favourite", "favorite",
            "centre", "center");

    private TextNormalizer() {
    }

    /** Lowercase, strip diacritics and unify typographic characters. No structural rewriting. */
    public static String fold(String input) {
        if (input == null) {
            return null;
        }
        String s = Normalizer.normalize(input, Normalizer.Form.NFKD);
        s = COMBINING_MARKS.matcher(s).replaceAll("");
        s = s.toLowerCase(Locale.ROOT)
                .replace('’', '\'').replace('‘', '\'')
                .replace('“', '"').replace('”', '"').replace('″', '"')
                .replace('–', '-').replace('—', '-').replace('×', 'x')
                .replace("'", "")
                .replace("&", " and ")
                .replace("+", " plus ");
        s = DOUBLE_A.matcher(s).replaceAll("aa");
        s = TRIPLE_A.matcher(s).replaceAll("aaa");
        return s;
    }

    /** Fold and collapse to alphanumeric tokens separated by single spaces. */
    public static String basic(String input) {
        if (input == null) {
            return null;
        }
        String s = NON_ALNUM.matcher(fold(input)).replaceAll(" ");
        s = applyTokenSynonyms(WHITESPACE.matcher(s).replaceAll(" ").strip());
        return s.isEmpty() ? null : s;
    }

    /**
     * Full title normalization: fold, canonicalize units/variants, strip punctuation (keeping decimal
     * points between digits), apply token synonyms and collapse whitespace.
     */
    public static String normalizeTitle(String input) {
        if (input == null) {
            return null;
        }
        String s = fold(input);
        s = canonicalizeUnits(s);
        s = HYPHEN_JOIN.matcher(s).replaceAll("");
        s = NON_ALNUM_KEEP_DOT.matcher(s).replaceAll(" ");
        s = STRAY_DOT.matcher(s).replaceAll(" ");
        s = WHITESPACE.matcher(s).replaceAll(" ").strip();
        s = applyTokenSynonyms(s);
        return s.isEmpty() ? null : s;
    }

    /** Rewrites unit and variant expressions into compact canonical tokens. Input must be folded. */
    static String canonicalizeUnits(String s) {
        s = replace(FRACTION, s, m -> decimal(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        s = N_IN_M.matcher(s).replaceAll("$1in$2");
        s = INCH_MARK.matcher(s).replaceAll("$1in ");
        s = INCH_WORD.matcher(s).replaceAll("$1in");
        s = TB.matcher(s).replaceAll("$1tb");
        s = GB.matcher(s).replaceAll("$1gb");
        s = MB.matcher(s).replaceAll("$1mb");
        s = MAH.matcher(s).replaceAll("$1mah");
        s = HERTZ.matcher(s).replaceAll("$1hz");
        s = FLUID_OZ.matcher(s).replaceAll("$1floz ");
        s = OUNCE.matcher(s).replaceAll("$1oz");
        s = MILLILITER.matcher(s).replaceAll("$1ml");
        s = LITER.matcher(s).replaceAll("$1l");
        s = QUART.matcher(s).replaceAll("$1qt");
        s = GALLON.matcher(s).replaceAll("$1gal");
        s = POUND.matcher(s).replaceAll("$1lb");
        s = KILOGRAM.matcher(s).replaceAll("$1kg");
        s = MILLIMETER.matcher(s).replaceAll("$1mm");
        s = CENTIMETER.matcher(s).replaceAll("$1cm");
        s = WATT.matcher(s).replaceAll("$1w");
        s = VOLT.matcher(s).replaceAll("$1v");
        s = PACK_OF.matcher(s).replaceAll("$1pk");
        s = N_PACK.matcher(s).replaceAll("$1pk");
        s = ORDINAL_GEN.matcher(s).replaceAll("gen$1");
        s = GEN_NUMBER.matcher(s).replaceAll("gen$1");
        s = replace(WORD_GEN, s, m -> "gen" + ORDINAL_WORDS.get(m.group(1)));
        s = replace(MARK, s, m -> "mk" + ROMAN.getOrDefault(m.group(1), m.group(1)));
        return s;
    }

    /** "1/2" becomes "0.5" so fractions do not leave stray bare numbers behind. */
    private static String decimal(int numerator, int denominator) {
        java.math.BigDecimal d = java.math.BigDecimal.valueOf(numerator)
                .divide(java.math.BigDecimal.valueOf(denominator), 3, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return d.toPlainString();
    }

    private static String applyTokenSynonyms(String s) {
        if (s.isEmpty()) {
            return s;
        }
        String[] tokens = s.split(" ");
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(TOKEN_SYNONYMS.getOrDefault(tokens[i], tokens[i]));
        }
        return sb.toString();
    }

    private static String replace(Pattern pattern, String input, Function<Matcher, String> replacement) {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder(input.length());
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
