
package com.google.refine.translation;

import java.util.Set;

/**
 * The languages supported by the Translate Column feature.
 *
 * <p>
 * To add a language, add its code to {@link #SOURCE_LANGUAGES} and {@link #TARGET_LANGUAGES}, add the same code to
 * {@code languageCodes} in {@code doTranslateColumn()} in {@code menu-edit-column.js}, and add a frontend i18n key
 * {@code "core-views/translate-lang-<code>"} to {@code main/webapp/modules/core/langs/translation-en.json}. The code
 * must be one the LibreTranslate server lists at {@code GET /languages}.
 */
public final class TranslationLanguages {

    /** Sentinel value that tells LibreTranslate to detect the source language. */
    public static final String AUTO = "auto";

    /**
     * The Chinese language code sent to LibreTranslate. Verify against {@code GET /languages} on your server; some
     * builds use {@code "zh-Hans"} instead.
     */
    public static final String ZH_CODE = "zh";

    /** Valid values for the source-language parameter (includes {@link #AUTO}). */
    public static final Set<String> SOURCE_LANGUAGES = Set.of(AUTO, "en", "es", ZH_CODE);

    /** Valid values for the target-language parameter. */
    public static final Set<String> TARGET_LANGUAGES = Set.of("en", "es", ZH_CODE);

    public static boolean isValidSource(String lang) {
        return SOURCE_LANGUAGES.contains(lang);
    }

    public static boolean isValidTarget(String lang) {
        return TARGET_LANGUAGES.contains(lang);
    }

    private TranslationLanguages() {
    }
}
