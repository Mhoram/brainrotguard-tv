package com.mhoram.brainrotguardtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [normalizeServerUrl] — the first-run setup screen's URL
 * parser. Behavior must stay backward compatible with previously saved URLs.
 */
class ServerUrlTest {

    @Test
    fun `bare host gets http scheme`() {
        assertEquals("http://myserver", normalizeServerUrl("myserver"))
    }

    @Test
    fun `host with port gets http scheme`() {
        assertEquals("http://192.168.1.50:8080", normalizeServerUrl("192.168.1.50:8080"))
    }

    @Test
    fun `http url is kept as-is`() {
        assertEquals("http://192.168.1.50:8080", normalizeServerUrl("http://192.168.1.50:8080"))
    }

    @Test
    fun `https scheme is preserved`() {
        assertEquals("https://brg.example.com", normalizeServerUrl("https://brg.example.com"))
    }

    @Test
    fun `https url with port and path is kept`() {
        assertEquals(
            "https://brg.example.com:8443/app",
            normalizeServerUrl("https://brg.example.com:8443/app"),
        )
    }

    @Test
    fun `trailing slash is stripped`() {
        assertEquals("http://192.168.1.50:8080", normalizeServerUrl("http://192.168.1.50:8080/"))
        assertEquals("http://192.168.1.50:8080/app", normalizeServerUrl("http://192.168.1.50:8080/app/"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("http://192.168.1.50:8080", normalizeServerUrl("  http://192.168.1.50:8080  "))
    }

    @Test
    fun `internal whitespace is not accepted as scheme prefix`() {
        // " http://x" with a leading space is trimmed first, so this parses fine
        assertEquals("http://x", normalizeServerUrl(" http://x"))
    }

    @Test
    fun `empty input is rejected`() {
        assertNull(normalizeServerUrl(""))
    }

    @Test
    fun `whitespace-only input is rejected`() {
        assertNull(normalizeServerUrl("   "))
    }

    @Test
    fun `scheme with empty host is rejected`() {
        assertNull(normalizeServerUrl("http://"))
        assertNull(normalizeServerUrl("https://"))
    }

    @Test
    fun `ip with digits-only host is accepted`() {
        assertEquals("http://10.0.0.1", normalizeServerUrl("10.0.0.1"))
    }

    @Test
    fun `tailscale-style hostname is accepted`() {
        assertEquals("http://brg-server:8080", normalizeServerUrl("brg-server:8080"))
    }
}
