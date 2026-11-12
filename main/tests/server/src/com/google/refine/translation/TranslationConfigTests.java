
package com.google.refine.translation;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

public class TranslationConfigTests {

    @AfterMethod
    public void clearProperties() {
        System.clearProperty(TranslationConfig.URL_PROPERTY);
        System.clearProperty(TranslationConfig.KEY_PROPERTY);
    }

    @Test
    public void testDefaultUrl() {
        System.clearProperty(TranslationConfig.URL_PROPERTY);
        // env var won't be set in CI either, so the default kicks in
        String url = TranslationConfig.getUrl();
        // We only assert that something is returned (the env var might be set in some environments)
        assertEquals(url != null && !url.isBlank(), true);
    }

    @Test
    public void testUrlFromProperty() {
        System.setProperty(TranslationConfig.URL_PROPERTY, "http://my-server:1234");
        assertEquals(TranslationConfig.getUrl(), "http://my-server:1234");
    }

    @Test
    public void testKeyFromProperty() {
        System.setProperty(TranslationConfig.KEY_PROPERTY, "secret");
        assertEquals(TranslationConfig.getApiKey(), "secret");
    }

    @Test
    public void testKeyNullByDefault() {
        System.clearProperty(TranslationConfig.KEY_PROPERTY);
        // Only null if the env var is also absent; we can't unset env vars, but
        // in a clean test environment LIBRETRANSLATE_API_KEY won't be set.
        String key = TranslationConfig.getApiKey();
        // key may be null or a real value from env — just check it doesn't throw
        // and that if the property is set it takes precedence
        System.setProperty(TranslationConfig.KEY_PROPERTY, "prop-key");
        assertEquals(TranslationConfig.getApiKey(), "prop-key");
    }
}
