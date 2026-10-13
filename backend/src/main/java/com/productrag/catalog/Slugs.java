package com.productrag.catalog;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** URL-friendly identifiers: lower-case ASCII letters and digits separated by single hyphens. */
public final class Slugs {

    public static final String PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*$";
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    private Slugs() {
    }

    /** "Men's Clothing" -> "mens-clothing", "Wi-Fi Support" -> "wi-fi-support". Empty when nothing usable remains. */
    public static String from(String text) {
        if (text == null) {
            return "";
        }
        String ascii = DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        String lower = ascii.toLowerCase(Locale.ROOT).replace("'", "").replace("&", " and ");
        String slug = NON_ALPHANUMERIC.matcher(lower).replaceAll("-");
        return slug.replaceAll("^-+|-+$", "");
    }

    /** The explicit slug when given, otherwise one derived from the name. */
    public static String resolve(String explicitSlug, String name) {
        return explicitSlug == null || explicitSlug.isBlank() ? from(name) : explicitSlug.trim().toLowerCase(Locale.ROOT);
    }
}
