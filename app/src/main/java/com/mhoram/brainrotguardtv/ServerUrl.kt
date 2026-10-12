package com.mhoram.brainrotguardtv

/**
 * Normalizes a user-typed server URL for the kiosk.
 *
 * Accepts "host", "host:port", "http://host[:port]/path" etc. and returns a
 * canonical URL: scheme defaulted to http://, trailing slashes stripped.
 * Returns null when the input can't possibly be a URL (empty, scheme-less
 * empty host).
 *
 * Standalone (no Android imports) so it can be unit-tested on the JVM.
 */
internal fun normalizeServerUrl(raw: String): String? {
    var url = raw.trim()
    if (url.isEmpty()) return null
    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
    return try {
        require(url.substringAfter("://").isNotEmpty())
        url.trimEnd('/')
    } catch (e: Exception) {
        null
    }
}
