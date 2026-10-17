package com.mhoram.brainrotguardtv

/**
 * Pure helpers for the TV kiosk's interaction with the BrainRotGuard web app.
 * Standalone (no Android imports) so everything is unit-testable on the JVM.
 */
internal object TvPageScripts {

    /**
     * True when a hover event may be dispatched to the WebView. Hover moves
     * force :hover recomputation and repaints in the page, so they are
     * throttled (default ~25 events/s) — the cursor simply jumps on screen
     * between dispatched hovers, which is imperceptible at TV distance.
     */
    internal fun shouldDispatchHover(
        lastDispatchAtMs: Long,
        nowMs: Long,
        minIntervalMs: Long = 40
    ): Boolean = nowMs - lastDispatchAtMs >= minIntervalMs

    /**
     * Scrolls the watch page's video player to the top of the viewport.
     * Without this the player's bottom controls (fullscreen button) start
     * below the fold on a TV and cost several D-pad scrolls to reach.
     */
    internal fun scrollPlayerIntoViewJs(): String = """
        (function() {
            var p = document.querySelector('.video-player');
            if (p) p.scrollIntoView({ block: 'start' });
        })();
    """.trimIndent()

    /**
     * Clicks the web app's fullscreen toggle button (#fs-toggle), if present.
     * Going through the page's own button keeps its fullscreenchange listeners
     * and fallback-fullscreen logic in the loop.
     */
    internal fun toggleFullscreenJs(): String = """
        (function() {
            var b = document.getElementById('fs-toggle');
            if (b) b.click();
        })();
    """.trimIndent()
}
