
package com.google.refine.translation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.testng.annotations.Test;

import com.google.refine.util.ParsingUtilities;

/**
 * Guards against the frontend language list drifting from {@link TranslationLanguages}: the dialog's
 * {@code languageCodes} array is hardcoded in JavaScript, so this test reads it as text and compares.
 */
public class TranslationLanguagesTests {

    // Paths are relative to the main/ module, where the server tests run.
    private static final Path MENU_JS = Path.of("webapp/modules/core/scripts/views/data-table/menu-edit-column.js");
    private static final Path TRANSLATION_EN = Path.of("webapp/modules/core/langs/translation-en.json");

    private static final Pattern LANGUAGE_CODES_ARRAY = Pattern.compile("var\\s+languageCodes\\s*=\\s*\\[([^\\]]*)\\]");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");

    /** Returns the codes in the {@code languageCodes} array of menu-edit-column.js, in order. */
    private List<String> frontendLanguageCodes() throws Exception {
        String js = Files.readString(MENU_JS, StandardCharsets.UTF_8);
        Matcher array = LANGUAGE_CODES_ARRAY.matcher(js);
        if (!array.find()) {
            fail("Could not find 'var languageCodes = [ ... ]' in " + MENU_JS
                    + ". If it was renamed or moved, update this test.");
        }
        List<String> codes = new ArrayList<>();
        Matcher quoted = QUOTED.matcher(array.group(1));
        while (quoted.find()) {
            codes.add(quoted.group(1));
        }
        return codes;
    }

    @Test
    public void testFrontendCodesMatchTargetLanguages() throws Exception {
        List<String> codes = frontendLanguageCodes();
        assertEquals(new TreeSet<>(codes).size(), codes.size(),
                "languageCodes in " + MENU_JS + " contains duplicates: " + codes);
        assertEquals(new TreeSet<>(codes), new TreeSet<>(TranslationLanguages.TARGET_LANGUAGES),
                "languageCodes in " + MENU_JS + " must match TranslationLanguages.TARGET_LANGUAGES."
                        + " Update whichever list is out of date.");
    }

    @Test
    public void testSourceLanguagesAreFrontendCodesPlusAuto() throws Exception {
        Set<String> expected = new TreeSet<>(frontendLanguageCodes());
        expected.add(TranslationLanguages.AUTO);
        assertEquals(new TreeSet<>(TranslationLanguages.SOURCE_LANGUAGES), expected,
                "TranslationLanguages.SOURCE_LANGUAGES must equal languageCodes in " + MENU_JS
                        + " plus \"" + TranslationLanguages.AUTO + "\". Update whichever list is out of date.");
    }

    @Test
    public void testEveryFrontendCodeHasI18nLabel() throws Exception {
        JsonNode messages = ParsingUtilities.mapper.readTree(
                Files.readString(TRANSLATION_EN, StandardCharsets.UTF_8));
        List<String> missing = new ArrayList<>();
        for (String code : frontendLanguageCodes()) {
            if (!messages.hasNonNull("core-views/translate-lang-" + code)) {
                missing.add("core-views/translate-lang-" + code);
            }
        }
        assertTrue(missing.isEmpty(), "Add these keys to " + TRANSLATION_EN + ": " + missing);
    }
}
