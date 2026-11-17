
package com.google.refine.translation;

/**
 * Thrown when the translation server rejects the request as unauthorized (HTTP 401 or 403), i.e. the API key is
 * missing or invalid.
 *
 * <p>
 * Like {@link TranslationServiceUnreachableException}, this aborts the whole translation run instead of following the
 * per-cell on-error policy: every cell would fail the same way, and the user would otherwise get a silently empty
 * column.
 */
public class TranslationAuthException extends TranslationException {

    public TranslationAuthException(String message) {
        super(message);
    }
}
