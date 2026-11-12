
package com.google.refine.translation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sends a single translation request to a LibreTranslate server.
 *
 * <p>
 * Error messages produced by this class never include the API key or the server URL, so they are safe to display in the
 * UI or store in cells.
 */
public class LibreTranslateClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    public LibreTranslateClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /**
     * Translates {@code text} from {@code sourceLang} to {@code targetLang}.
     *
     * @param sourceLang
     *            BCP-47 language code or {@code "auto"} for detection
     * @param targetLang
     *            BCP-47 language code
     * @return the translated text
     * @throws TranslationException
     *             if the request fails or the response is unusable
     */
    public String translate(String text, String sourceLang, String targetLang)
            throws TranslationException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("q", text);
        body.put("source", sourceLang);
        body.put("target", targetLang);
        body.put("format", "text");
        if (apiKey != null && !apiKey.isBlank()) {
            body.put("api_key", apiKey);
        }

        String bodyStr;
        try {
            bodyStr = MAPPER.writeValueAsString(body);
        } catch (IOException e) {
            throw new TranslationException("Failed to serialize request body", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/translate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8))
                .timeout(TIMEOUT)
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Connection-level failure: the server itself is unreachable (Decision D6).
            throw new TranslationServiceUnreachableException("Translation service unreachable", e);
        } catch (InterruptedException e) {
            // The thread is interrupted when the user cancels the process; not a service failure.
            Thread.currentThread().interrupt();
            throw new TranslationException("Translation interrupted", e);
        }

        int status = response.statusCode();
        if (status != 200) {
            String errorMsg = extractErrorMessage(response.body());
            throw new TranslationException("HTTP " + status + (errorMsg != null ? ": " + errorMsg : ""));
        }

        try {
            JsonNode json = MAPPER.readTree(response.body());
            JsonNode translated = json.get("translatedText");
            if (translated == null || translated.isNull()) {
                throw new TranslationException("Response missing translatedText field");
            }
            return translated.asText();
        } catch (IOException e) {
            throw new TranslationException("Failed to parse translation response", e);
        }
    }

    /** Extracts the {@code "error"} field from a JSON response body, never leaking the API key. */
    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode json = MAPPER.readTree(body);
            JsonNode error = json.get("error");
            if (error != null && !error.isNull()) {
                String msg = error.asText();
                if (apiKey != null && !apiKey.isBlank()) {
                    msg = msg.replace(apiKey, "[redacted]");
                }
                return msg;
            }
        } catch (IOException e) {
            // ignore parse errors on error responses
        }
        return null;
    }
}
