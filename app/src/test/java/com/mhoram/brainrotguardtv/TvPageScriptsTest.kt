package com.mhoram.brainrotguardtv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [TvPageScripts] — the injected-page-JS builders and the
 * hover throttle used by the TV pointer/scroll pipeline.
 */
class TvPageScriptsTest {

    // ------------------------------------------------------ hover throttle

    @Test
    fun `first hover is always dispatched`() {
        assertTrue(TvPageScripts.shouldDispatchHover(lastDispatchAtMs = 0, nowMs = 100))
    }

    @Test
    fun `hover within the interval is suppressed`() {
        assertFalse(TvPageScripts.shouldDispatchHover(lastDispatchAtMs = 1000, nowMs = 1039))
    }

    @Test
    fun `hover at exactly the interval is dispatched`() {
        assertTrue(TvPageScripts.shouldDispatchHover(lastDispatchAtMs = 1000, nowMs = 1040))
    }

    @Test
    fun `hover after the interval is dispatched`() {
        assertTrue(TvPageScripts.shouldDispatchHover(lastDispatchAtMs = 1000, nowMs = 1100))
    }

    @Test
    fun `custom interval is honored`() {
        assertFalse(TvPageScripts.shouldDispatchHover(lastDispatchAtMs = 1000, nowMs = 1099, minIntervalMs = 100))
        assertTrue(TvPageScripts.shouldDispatchHover(lastDispatchAtMs = 1000, nowMs = 1100, minIntervalMs = 100))
    }

    // ------------------------------------------------------ injected JS

    @Test
    fun `scroll script targets the video player element`() {
        val js = TvPageScripts.scrollPlayerIntoViewJs()
        assertTrue(js.contains(".video-player"))
        assertTrue(js.contains("scrollIntoView"))
        assertTrue(js.contains("'start'"))
    }

    @Test
    fun `scroll script tolerates pages without a player`() {
        val js = TvPageScripts.scrollPlayerIntoViewJs()
        assertTrue("must guard against a missing player", js.contains("if (p)"))
    }

    @Test
    fun `fullscreen script clicks the page's own toggle button`() {
        val js = TvPageScripts.toggleFullscreenJs()
        assertTrue(js.contains("fs-toggle"))
        assertTrue(js.contains(".click()"))
    }

    @Test
    fun `fullscreen script tolerates pages without a toggle`() {
        val js = TvPageScripts.toggleFullscreenJs()
        assertTrue("must guard against a missing button", js.contains("if (b)"))
    }

    @Test
    fun `watch style forces the fullscreen button visible`() {
        val js = TvPageScripts.watchPageStyleJs()
        assertTrue(js.contains(".fs-toggle"))
        assertTrue(js.contains("opacity: 1"))
    }

    @Test
    fun `watch style pins the button to the top-right and enlarges it`() {
        val js = TvPageScripts.watchPageStyleJs()
        assertTrue(js.contains("top: 12px"))
        assertTrue(js.contains("bottom: auto"))
        assertTrue(js.contains("width: 56px"))
    }
}
