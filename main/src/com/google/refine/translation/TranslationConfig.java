
package com.google.refine.translation;

/**
 * Reads the LibreTranslate server URL and optional API key.
 *
 * <p>
 * Each value is checked in this order:
 * <ol>
 * <li>JVM system property (e.g. {@code -Drefine.translate.url=...})</li>
 * <li>Environment variable (e.g. {@code LIBRETRANSLATE_URL=...})</li>
 * <li>Built-in default (URL only: {@value #DEFAULT_URL})</li>
 * </ol>
 *
 * <p>
 * To configure without committing credentials, copy the commented-out example lines from {@code refine.ini} into
 * {@code refine-dev.ini} (which is gitignored) and start OpenRefine with {@code ./refine -c refine-dev.ini}. The ini
 * script exports those lines as environment variables, which this class then reads.
 */
public final class TranslationConfig {

    public static final String URL_PROPERTY = "refine.translate.url";
    public static final String KEY_PROPERTY = "refine.translate.apikey";
    public static final String URL_ENV = "LIBRETRANSLATE_URL";
    public static final String KEY_ENV = "LIBRETRANSLATE_API_KEY";
    public static final String DEFAULT_URL = "http://localhost:5000";

    public static String getUrl() {
        String v = System.getProperty(URL_PROPERTY);
        if (v != null && !v.isBlank()) return v;
        v = System.getenv(URL_ENV);
        if (v != null && !v.isBlank()) return v;
        return DEFAULT_URL;
    }

    /** Returns the API key, or {@code null} if none is configured. */
    public static String getApiKey() {
        String v = System.getProperty(KEY_PROPERTY);
        if (v != null && !v.isBlank()) return v;
        v = System.getenv(KEY_ENV);
        if (v != null && !v.isBlank()) return v;
        return null;
    }

    private TranslationConfig() {
    }
}
