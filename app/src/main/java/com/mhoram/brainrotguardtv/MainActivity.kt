package com.mhoram.brainrotguardtv

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import android.widget.Button
import android.widget.Toast
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * WebView kiosk for BrainRotGuard (Android TV / Google TV).
 *
 * - First launch: prompts for the BrainRotGuard server URL (e.g. http://192.168.1.50:8080)
 * - Subsequent launches: loads it fullscreen, keeps the screen on
 * - D-pad MENU button: reopen the URL editor (to change server)
 * - BACK: navigates in-app history, never exits the kiosk
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private var webView: WebView? = null
    private var voiceActive = false
    private var voiceReady = false
    private var voiceCooldownUntil = 0L

    // Pointer mode: a virtual cursor over the WebView, driven by the D-pad.
    private var mouseMode = false
    private var cursor: PointerView? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private val cursorSizePx by lazy {
        (30 * resources.displayMetrics.density).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("kiosk", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Never connect this window to the IME: focused text fields in the
        // WebView otherwise keep an IME session alive, and on some TVs (TCL)
        // the IME then swallows OK/ENTER or even converts them into typed
        // characters. Text entry happens in native dialogs (own windows) so
        // the on-screen keyboard still works there.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        root = FrameLayout(this)
        setContentView(root)
        val url = serverUrl()
        if (url == null) showSetup("") else showWebView(url)
    }

    private fun serverUrl(): String? =
        prefs.getString(KEY_URL, null)?.trim()?.takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------- setup

    /**
     * Server URL editor. A native dialog (own window) so the on-screen
     * keyboard works even though the activity window blocks the IME.
     */
    private fun showSetup(prefill: String) {
        exitMouseMode()
        val input = EditText(this).apply {
            hint = getString(R.string.setup_hint)
            setText(prefill)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.BLACK)
            setHintTextColor(Color.DKGRAY)
            setBackgroundColor(Color.WHITE)
            val fieldPad = (12 * resources.displayMetrics.density).toInt()
            setPadding(fieldPad, fieldPad, fieldPad, fieldPad)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.setup_title)
            .setView(input)
            .setPositiveButton(R.string.setup_save) { d, _ ->
                val url = normalizeUrl(input.text.toString())
                if (url != null) {
                    prefs.edit().putString(KEY_URL, url).apply()
                    showWebView(url)
                } else {
                    Toast.makeText(this, getString(R.string.setup_invalid), Toast.LENGTH_LONG).show()
                    showSetup(input.text.toString())
                }
            }
            .create()
        // First run: no way back to an empty screen — Save is mandatory.
        dialog.setCancelable(serverUrl() != null)
        dialog.show()
        input.requestFocus()
    }

    private fun normalizeUrl(raw: String): String? {
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

    // --------------------------------------------------------------- webview

    private fun showWebView(url: String) {
        root.removeAllViews()
        cursor = null  // was detached by removeAllViews; will be re-created if needed
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false  // approved videos auto-start from pending page
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE  // server is plain http on LAN
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = false
            allowContentAccess = false
        }
        wv.setBackgroundColor(Color.BLACK)
        wv.addJavascriptInterface(VoiceSearchBridge(), "BRGVoice")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Keep everything (including YouTube embeds) inside the kiosk.
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                installTextInputHook(view)
                // Inject a mic button next to each search box. Voice search is
                // explicit (button / remote mic key) — never automatic on focus,
                // so the search box can still be used for typing.
                // Guarded per page load; the bridge is one-way (opens a dialog
                // only) so exposure to iframes is a mild annoyance at worst.
                view.evaluateJavascript(
                    """
                    (function() {
                        if (window.__brgVoiceHookInstalled) return;
                        window.__brgVoiceHookInstalled = true;
                        document.querySelectorAll('form[action="/search"]').forEach(function(form) {
                            var q = form.querySelector('input[name="q"]');
                            if (!q || form.querySelector('.brg-voice-btn')) return;
                            var btn = document.createElement('button');
                            btn.type = 'button';
                            btn.className = 'brg-voice-btn';
                            btn.textContent = '\uD83C\uDFA4';
                            btn.setAttribute('aria-label', 'Voice search');
                            btn.setAttribute('title', 'Voice search');
                            // Match the app's .btn-primary look via its own theme vars.
                            btn.style.cssText = [
                                'padding:0.625rem 0.875rem',
                                'margin-right:8px',
                                'font-size:0.975rem',
                                'line-height:1',
                                'font-weight:600',
                                'color:#fff',
                                'border:none',
                                'border-radius:var(--radius-btn,8px)',
                                'background:var(--bg-card-hover,#1e1e45)',
                                'cursor:pointer',
                                'flex-shrink:0'
                            ].join(';');
                            btn.addEventListener('click', function(e) {
                                e.preventDefault();
                                BRGVoice.onMicClicked();
                            });
                            q.insertAdjacentElement('beforebegin', btn);
                        });
                    })();
                    """.trimIndent(),
                    null
                )
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                // Only react to main-frame failures (subresources fail all the time, e.g. favicon)
                if (request?.isForMainFrame == true) showOffline(url)
            }
        }

        wv.loadUrl(url)
        root.addView(wv)
        webView = wv
        wv.requestFocus()
    }

    private fun showOffline(url: String) {
        root.removeAllViews()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = android.view.Gravity.CENTER
        }
        val msg = TextView(this).apply {
            text = getString(R.string.offline_message, url)
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        val retry = Button(this).apply {
            text = getString(R.string.offline_retry)
            setOnClickListener { showWebView(url) }
        }
        val changeServer = Button(this).apply {
            text = getString(R.string.offline_change_server)
            setOnClickListener { showSetup(prefill = serverUrl() ?: "") }
        }
        box.addView(msg)
        box.addView(retry)
        box.addView(changeServer)
        retry.requestFocus()
        root.addView(box)
        exitMouseMode()
        cursor = null  // was detached by removeAllViews above
        webView?.destroy()
        webView = null
    }

    // ----------------------------------------------------------------- input

    // All key handling happens here, NOT in onKeyDown/onKeyUp/onKeyLongPress:
    // the WebView sits first in the key-dispatch chain and consumes OK/ENTER/
    // directional keys for its own focus navigation, so the Activity-level
    // fallbacks never fire for keys pressed while web content is showing.
    // Long-press is timed manually (framework onKeyLongPress depends on the
    // same unreachable fallback path), which also copes with remotes that
    // never emit key-repeat events.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var longPressKeyCode = 0
    private var longPressRunnable: Runnable? = null
    private var longPressFired = false

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.isLongPress) {
                    // Key explicitly flagged as long-press (injected events,
                    // some remotes). If our own timer already fired for this
                    // key, just consume the rest of the hold.
                    if (longPressFired && longPressKeyCode == event.keyCode) return true
                    disarmLongPress()
                    val action = longPressAction(event.keyCode)
                    if (action != null) {
                        longPressKeyCode = event.keyCode
                        longPressFired = true
                        action.run()
                        return true
                    }
                } else if (event.repeatCount == 0) {
                    armLongPress(event.keyCode)
                }
                if (handleKeyDown(event.keyCode, event)) return true
            }
            KeyEvent.ACTION_UP -> {
                val wasLongPress = longPressFired && longPressKeyCode == event.keyCode
                disarmLongPress()
                if (wasLongPress) return true  // consumed by the long-press action
                if (handleKeyUp(event.keyCode, event)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun longPressAction(keyCode: Int): Runnable? = when (keyCode) {
        // Universal escape hatch back to setup, for remotes with no MENU button.
        KeyEvent.KEYCODE_BACK -> Runnable { showSetup(prefill = serverUrl() ?: "") }
        // Holding OK toggles pointer mode (only over the web content).
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
            if (webView != null && !voiceActive) Runnable { toggleMouseMode() } else null
        else -> null
    }

    private fun armLongPress(keyCode: Int) {
        disarmLongPress()
        val action = longPressAction(keyCode) ?: return
        longPressKeyCode = keyCode
        val runnable = Runnable {
            longPressFired = true
            action.run()
        }
        longPressRunnable = runnable
        mainHandler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun disarmLongPress() {
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null
        longPressFired = false
        longPressKeyCode = 0
    }

    private fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Remote mic / search button: voice-search YouTube through the web app
        if (keyCode == KeyEvent.KEYCODE_SEARCH ||
            keyCode == KeyEvent.KEYCODE_ASSIST ||
            keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            startVoiceSearch()
            return true
        }
        // MENU (D-pad / remote menu button) reopens the server URL editor
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSetup(prefill = serverUrl() ?: "")
            return true
        }
        // In pointer mode the D-pad steers the cursor instead of moving web focus.
        if (mouseMode && isDirectional(keyCode)) {
            moveCursor(keyCode, event.repeatCount)
            return true
        }
        // OK/ENTER in pointer mode clicks at the cursor. In normal mode fall
        // through so the WebView clicks the focused element itself.
        if (mouseMode && isCenterKey(keyCode)) {
            if (event.repeatCount == 0) dispatchTap()
            return true
        }
        return false
    }

    private fun handleKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // BACK while listening cancels voice search, not page history.
            if (voiceActive) {
                speechRecognizer?.cancel()
                endVoiceSession()
                return true
            }
            // BACK in pointer mode leaves pointer mode first.
            if (mouseMode) {
                exitMouseMode()
                return true
            }
            // Stay in the kiosk: short-press BACK goes back within the web app.
            val wv = webView
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                Toast.makeText(this, getString(R.string.back_hint), Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return false
    }

    // --------------------------------------------------------- pointer mode

    private fun isDirectional(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

    private fun isCenterKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER

    private fun toggleMouseMode() {
        if (mouseMode) exitMouseMode() else enterMouseMode()
        Toast.makeText(
            this,
            getString(if (mouseMode) R.string.pointer_mode_on else R.string.pointer_mode_off),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun enterMouseMode() {
        val wv = webView ?: return
        if (cursor == null) {
            val v = PointerView(this)
            root.addView(
                v,
                FrameLayout.LayoutParams(cursorSizePx, cursorSizePx, android.view.Gravity.TOP or android.view.Gravity.START)
            )
            cursor = v
        }
        // Start centered (root has already been laid out when the WebView shows).
        if (cursorX == 0f && cursorY == 0f) {
            cursorX = (wv.width - cursorSizePx) / 2f
            cursorY = (wv.height - cursorSizePx) / 2f
        }
        mouseMode = true
        cursor?.visibility = View.VISIBLE
        updateCursor()
    }

    private fun exitMouseMode() {
        mouseMode = false
        cursor?.visibility = View.GONE
    }

    private fun moveCursor(keyCode: Int, repeatCount: Int) {
        // Small base steps with gentle acceleration while the direction is held.
        val step = 8f * resources.displayMetrics.density *
            (1f + (repeatCount.coerceAtMost(24) / 12f))
        var dx = 0f
        var dy = 0f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> dy = -step
            KeyEvent.KEYCODE_DPAD_DOWN -> dy = step
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -step
            KeyEvent.KEYCODE_DPAD_RIGHT -> dx = step
        }
        val rawX = cursorX + dx
        val rawY = cursorY + dy
        cursorX = rawX.coerceIn(0f, (root.width - cursorSizePx).toFloat())
        cursorY = rawY.coerceIn(0f, (root.height - cursorSizePx).toFloat())
        // Pushing the pointer past the top/bottom edge scrolls the page
        // (faster than the cursor step — one press ≈ 3 cursor-steps of scroll).
        if (dy != 0f && rawY != cursorY) scrollPage(dy * 3f)
        updateCursor()
        dispatchHover()
    }

    /** Scrolls the page under the pointer, like a mouse-wheel tick. */
    private fun scrollPage(amountPx: Float) {
        val wv = webView ?: return
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val (x, y) = cursorPoint()
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            // Positive AXIS_VSCROLL = wheel up; moving the cursor down (dy>0)
            // should scroll the content down, hence the negation. One unit
            // scrolls roughly 64px.
            setAxisValue(MotionEvent.AXIS_VSCROLL, -amountPx / 64f)
        }
        wv.dispatchGenericMotionEvent(
            MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL, 1,
                arrayOf(props), arrayOf(coords),
                0, 0, 0f, 0f, 0, 0,
                android.view.InputDevice.SOURCE_MOUSE, 0
            )
        )
    }

    private fun updateCursor() {
        cursor?.let {
            it.translationX = cursorX
            it.translationY = cursorY
        }
    }

    /** Center of the cursor in WebView coordinates. */
    private fun cursorPoint(): FloatArray {
        val half = cursorSizePx / 2f
        return floatArrayOf(cursorX + half, cursorY + half)
    }

    private fun dispatchHover() {
        val wv = webView ?: return
        val (x, y) = cursorPoint()
        val now = SystemClock.uptimeMillis()
        wv.dispatchGenericMotionEvent(
            mouseEvent(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, buttonState = 0)
        )
    }

    private fun dispatchTap() {
        val wv = webView ?: return
        val (x, y) = cursorPoint()
        val now = SystemClock.uptimeMillis()
        // This Chromium build only turns touch-stream taps (finger semantics)
        // into clicks — mouse tool-type events are dropped, and generic-stream
        // ACTION_BUTTON_PRESS is ignored too. So click as a finger; the
        // mouse-channel hover/scroll events elsewhere still drive hover styles
        // and embedded-player controls.
        wv.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        wv.dispatchTouchEvent(MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0))
    }

    /** Builds a full pointer event so Chromium treats it as a real mouse. */
    private fun mouseEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int
    ): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, 1,
            arrayOf(props), arrayOf(coords),
            0, buttonState, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_MOUSE, 0
        )
    }

    /** Small circular cursor: white dot with a dark ring, visible on any background. */
    private inner class PointerView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2196F3.toInt()  // material blue
            style = Paint.Style.FILL
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * resources.displayMetrics.density
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = width / 2f - ring.strokeWidth
            canvas.drawCircle(width / 2f, height / 2f, r, fill)
            canvas.drawCircle(width / 2f, height / 2f, r, ring)
        }
    }

    // ---------------------------------------------------- text entry hook

    /**
     * The activity window blocks the IME (FLAG_ALT_FOCUSABLE_IM), so text
     * fields in the page can't be typed into directly. Instead, clicking or
     * Enter-activating a text field opens a native editor dialog (own window,
     * keyboard works) and writes the result back into the field.
     *
     * Triggered on click/Enter only — NOT on focus — so D-pad focus
     * navigation through the page doesn't pop dialogs.
     */
    private fun installTextInputHook(view: WebView) {
        view.evaluateJavascript(
            """
            (function() {
                if (window.__brgTextInputHook) return;
                window.__brgTextInputHook = true;
                function isTextInput(el) {
                    if (!el || el.tagName !== 'INPUT') return false;
                    var t = (el.type || 'text').toLowerCase();
                    return t === 'text' || t === 'search' || t === 'url' || t === '';
                }
                function openEditor(el) {
                    if (!el.dataset.brgId) el.dataset.brgId = 'brg' + Math.random().toString(36).slice(2, 8);
                    var id = el.dataset.brgId, val = el.value || '';
                    el.blur();
                    BRGVoice.onTextInput(id, val);
                }
                document.addEventListener('click', function(e) {
                    if (isTextInput(e.target)) openEditor(e.target);
                });
                document.addEventListener('keydown', function(e) {
                    if (e.key !== 'Enter' && e.keyCode !== 13) return;
                    var el = document.activeElement;
                    if (isTextInput(el)) { e.preventDefault(); openEditor(el); }
                });
            })();
            """ .trimIndent(),
            null
        )
    }

    private fun showTextInputDialog(id: String, value: String) {
        if (webView == null || voiceActive) return
        val input = EditText(this).apply {
            setText(value)
            setSingleLine(true)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.text_input_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val js = "(function(){var el=document.querySelector('input[data-brg-id=\"$id\"]');" +
                    "if(!el)return;el.value=" + JSONObject.quote(input.text.toString()) + ";" +
                    "el.dispatchEvent(new Event('input',{bubbles:true}));})();"
                webView?.evaluateJavascript(js, null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----------------------------------------------------------------- voice

    /** Bridges injected page JS to native actions (one-way, opens UI only). */
    inner class VoiceSearchBridge {
        @JavascriptInterface
        fun onMicClicked() {
            runOnUiThread {
                if (voiceActive || System.currentTimeMillis() < voiceCooldownUntil) return@runOnUiThread
                startVoiceSearch()
            }
        }

        @JavascriptInterface
        fun onTextInput(id: String, value: String) {
            runOnUiThread { showTextInputDialog(id, value) }
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceOverlay: TextView? = null
    private var voiceStartRetries = 0

    private fun startVoiceSearch() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, getString(R.string.voice_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
            return
        }
        startListening()
    }

    private fun createRecognizer(): SpeechRecognizer {
        // Android TV: the default service resolution can silently hang, so prefer
        // katniss (the ATV voice stack) explicitly when present.
        val katniss = ComponentName(
            "com.google.android.katniss",
            "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService"
        )
        return try {
            packageManager.getServiceInfo(katniss, 0)
            SpeechRecognizer.createSpeechRecognizer(this, katniss)
        } catch (e: PackageManager.NameNotFoundException) {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
    }

    private fun startListening() {
        voiceActive = true
        voiceReady = false
        voiceStartRetries = 0
        // The input focus that triggered us also pops the on-screen keyboard —
        // hide it while listening.
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(webView?.windowToken, 0)
        val pad = (32 * resources.displayMetrics.density).toInt()
        val overlay = TextView(this).apply {
            text = getString(R.string.voice_listening)
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(pad, pad, pad, pad)
        }
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        )
        voiceOverlay = overlay

        val recognizer = createRecognizer()
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val query = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                endVoiceSession()
                val base = serverUrl()
                if (!query.isNullOrBlank() && base != null) {
                    // The web app's search is a plain GET form — navigate directly.
                    webView?.loadUrl("$base/search?q=" + Uri.encode(query))
                }
            }

            override fun onError(error: Int) {
                endVoiceSession()
                // ERROR_CLIENT = our own cancel() — stay silent for that one.
                if (error != SpeechRecognizer.ERROR_CLIENT) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.voice_error) + " (code $error)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {
                voiceReady = true
                voiceOverlay?.text = getString(R.string.voice_speak_now)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        // Watchdog: if the service never reports ready, fail cleanly instead of
        // hanging on the overlay forever. The first invocation after boot often
        // exceeds the timeout (cold GMS/Katniss start) while actually working,
        // so retry once before declaring the mic unavailable.
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer.startListening(intent)
        overlay.postDelayed({
            if (voiceActive && !voiceReady && speechRecognizer === recognizer) {
                if (voiceStartRetries++ < 1) {
                    recognizer.cancel()
                    recognizer.startListening(intent)
                } else {
                    endVoiceSession()
                    Toast.makeText(this, getString(R.string.voice_unavailable), Toast.LENGTH_LONG).show()
                }
            }
        }, 6000)
    }

    private fun endVoiceSession() {
        voiceActive = false
        // Swallow the autofocus that fires when the results page loads.
        voiceCooldownUntil = System.currentTimeMillis() + 4000
        speechRecognizer?.destroy()
        speechRecognizer = null
        voiceOverlay?.let { root.removeView(it) }
        voiceOverlay = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                Toast.makeText(this, getString(R.string.voice_unavailable), Toast.LENGTH_SHORT).show()
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // ------------------------------------------------------------- immersive

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                .or(View.SYSTEM_UI_FLAG_FULLSCREEN)
                .or(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
                .or(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                .or(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                .or(View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val KEY_URL = "server_url"
        private const val REQ_RECORD_AUDIO = 42
    }
}
