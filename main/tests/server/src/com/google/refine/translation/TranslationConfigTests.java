
package com.google.refine.translation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import org.testng.SkipException;
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
        // Env vars can't be unset from Java; skip rather than pass vacuously if one is present.
        String env = System.getenv(TranslationConfig.URL_ENV);
        if (env != null && !env.isBlank()) {
            throw new SkipException(TranslationConfig.URL_ENV + " is set; cannot verify default URL");
        }
        assertEquals(TranslationConfig.getUrl(), TranslationConfig.DEFAULT_URL);
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
        // Env vars can't be unset from Java; skip rather than pass vacuously if one is present.
        String env = System.getenv(TranslationConfig.KEY_ENV);
        if (env != null && !env.isBlank()) {
            throw new SkipException(TranslationConfig.KEY_ENV + " is set; cannot verify default API key");
        }
        assertNull(TranslationConfig.getApiKey());
    }
}
