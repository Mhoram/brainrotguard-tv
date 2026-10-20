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
     * True when an OK/ENTER press qualifies as the second press of a
     * double-press (within [windowMs] of the previous one). [lastPressAtMs]
     * of 0 means "no previous press" and never counts as a double-press.
     */
    internal fun isDoublePress(
        lastPressAtMs: Long,
        nowMs: Long,
        windowMs: Long = 400
    ): Boolean = lastPressAtMs > 0 && nowMs - lastPressAtMs <= windowMs

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
     * TV overrides for the watch page's fullscreen button. The server's
     * .fs-toggle is bottom-right of the player and opacity:0 until hovered —
     * both wrong for a TV: the bottom edge is below the fold on a 16:9 screen,
     * and nothing ever hovers. Pin it to the top-right (visible once the
     * player is scrolled top-aligned) and make it always-on and bigger.
     */
    internal fun watchPageStyleJs(): String = """
        (function() {
            var s = document.createElement('style');
            s.textContent =
                '.fs-toggle{' +
                '  opacity: 1 !important;' +
                '  top: 12px !important;' +
                '  bottom: auto !important;' +
                '  right: 12px !important;' +
                '  width: 56px !important;' +
                '  height: 56px !important;' +
                '  z-index: 12;' +
                '}';
            document.head.appendChild(s);
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

    /**
     * Like [toggleFullscreenJs], but also resumes playback after entering
     * fullscreen. Used for the pointer-mode double-press shortcut: the first
     * tap of the double-press lands on the player and pauses it, so entering
     * fullscreen would otherwise start paused. Resuming is harmless when the
     * first tap clicked something else (the video was already playing).
     */
    internal fun doublePressFullscreenJs(): String = """
        (function() {
            var b = document.getElementById('fs-toggle');
            if (!b) return;
            b.click();
            if (typeof player !== 'undefined' && player && player.playVideo) {
                try { player.playVideo(); } catch (e) {}
            }
        })();
    """.trimIndent()
}
