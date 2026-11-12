
package com.google.refine.translation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.testng.annotations.Test;

public class LibreTranslateClientTests {

    private LibreTranslateClient client(MockWebServer server, String apiKey) {
        return new LibreTranslateClient(server.url("/").toString(), apiKey);
    }

    @Test
    public void testSuccessfulTranslation() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder()
                    .body("{\"translatedText\":\"Hola\"}")
                    .build());

            String result = client(server, null).translate("Hello", "en", "es");
            assertEquals(result, "Hola");

            RecordedRequest req = server.takeRequest();
            String body = req.getBody().utf8();
            assertTrue(body.contains("\"q\":\"Hello\""), "Body: " + body);
            assertTrue(body.contains("\"source\":\"en\""), "Body: " + body);
            assertTrue(body.contains("\"target\":\"es\""), "Body: " + body);
            assertFalse(body.contains("api_key"), "api_key should not be present when null");
        }
    }

    @Test
    public void testApiKeyIsSentWhenProvided() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder()
                    .body("{\"translatedText\":\"Hola\"}")
                    .build());

            client(server, "mykey").translate("Hello", "en", "es");

            RecordedRequest req = server.takeRequest();
            String body = req.getBody().utf8();
            assertTrue(body.contains("\"api_key\":\"mykey\""), "Body: " + body);
        }
    }

    @Test
    public void testServerErrorIsReportedWithoutLeakingKey() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder()
                    .code(403)
                    .body("{\"error\":\"Invalid API key\"}")
                    .build());

            TranslationException e = expectThrows(TranslationException.class,
                    () -> client(server, "secret-key").translate("hello", "en", "es"));

            assertTrue(e.getMessage().contains("403"), e.getMessage());
            assertFalse(e.getMessage().contains("secret-key"),
                    "Key must not appear in error: " + e.getMessage());
        }
    }

    @Test
    public void testErrorWithoutJsonBody() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder()
                    .code(500)
                    .body("Internal Server Error")
                    .build());

            TranslationException e = expectThrows(TranslationException.class,
                    () -> client(server, null).translate("hello", "en", "es"));

            assertTrue(e.getMessage().contains("500"), e.getMessage());
        }
    }

    @Test
    public void testMissingTranslatedTextField() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder()
                    .body("{\"someOtherField\":\"value\"}")
                    .build());

            assertThrows(TranslationException.class,
                    () -> client(server, null).translate("hello", "en", "es"));
        }
    }

    @Test
    public void testTrailingSlashNormalized() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder()
                    .body("{\"translatedText\":\"Hola\"}")
                    .build());

            // base URL with trailing slash — should not produce //translate
            String urlWithSlash = server.url("/").toString();
            String result = new LibreTranslateClient(urlWithSlash, null).translate("Hello", "en", "es");
            assertEquals(result, "Hola");
        }
    }

    @Test
    public void testUnreachableServerThrowsUnreachableException() throws Exception {
        String url;
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            url = server.url("/").toString();
        } // server is now closed: nothing listens on that port

        expectThrows(TranslationServiceUnreachableException.class,
                () -> new LibreTranslateClient(url, null).translate("hello", "en", "es"));
    }

    @Test
    public void testHttpErrorIsNotReportedAsUnreachable() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(500).build());

            TranslationException e = expectThrows(TranslationException.class,
                    () -> client(server, null).translate("hello", "en", "es"));
            assertFalse(e instanceof TranslationServiceUnreachableException,
                    "An HTTP error means the server is up; it must follow the on-error policy");
        }
    }
}
