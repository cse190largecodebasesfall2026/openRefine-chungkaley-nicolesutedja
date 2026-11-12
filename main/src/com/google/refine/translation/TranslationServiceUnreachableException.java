
package com.google.refine.translation;

/**
 * Thrown when the translation server cannot be reached at all (connection refused, unknown host, timeout).
 *
 * <p>
 * Unlike other {@link TranslationException}s, which affect a single cell and are handled by the on-error policy, this
 * aborts the whole translation run: if the server is down, every cell would fail and the user would otherwise get a
 * silently empty column (Decision D6).
 */
public class TranslationServiceUnreachableException extends TranslationException {

    public TranslationServiceUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
