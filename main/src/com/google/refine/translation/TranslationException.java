package com.google.refine.translation;

/**
 * Thrown when a single cell translation fails.
 *
 * <p>The message is safe to show to users and store in cells: it never contains
 * the API key or the full server URL.
 */
public class TranslationException extends Exception {

    public TranslationException(String message) {
        super(message);
    }

    public TranslationException(String message, Throwable cause) {
        super(message, cause);
    }
}
