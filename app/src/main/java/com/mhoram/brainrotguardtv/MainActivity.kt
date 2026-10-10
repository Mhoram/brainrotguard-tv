package com.mhoram.brainrotguardtv

import android.Manifest
import android.app.Activity
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
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
    private var backLongPressFired = false
    private var centerLongPressFired = false
    private var voiceActive = false
    private var voiceReady = false
    private var voiceCooldownUntil = 0L

    // Pointer mode: a virtual cursor over the WebView, driven by the D-pad.
    private var mouseMode = false
    private var cursor: PointerView? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private val cursorSizePx by lazy {
        (20 * resources.displayMetrics.density).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("kiosk", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        setContentView(root)
        val url = serverUrl()
        if (url == null) showSetup(prefill = "") else showWebView(url)
    }

    private fun serverUrl(): String? =
        prefs.getString(KEY_URL, null)?.trim()?.takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------- setup

    private fun showSetup(prefill: String) {
        webView?.destroy()
        webView = null
        root.removeAllViews()
        cursor = null  // was detached by removeAllViews; will be re-created if needed

        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
            gravity = android.view.Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = getString(R.string.setup_title)
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.setup_hint)
            setText(prefill)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            // Explicit light field + dark text: default TV theme styling can render
            // the field light, making white text unreadable.
            setTextColor(Color.BLACK)
            setHintTextColor(Color.DKGRAY)
            setBackgroundColor(Color.WHITE)
            val fieldPad = (12 * resources.displayMetrics.density).toInt()
            setPadding(fieldPad, fieldPad, fieldPad, fieldPad)
        }
        val save = Button(this).apply {
            text = getString(R.string.setup_save)
            isFocusableInTouchMode = true
            setOnClickListener {
                val url = normalizeUrl(input.text.toString())
                if (url != null) {
                    prefs.edit().putString(KEY_URL, url).apply()
                    showWebView(url)
                } else {
                    input.error = getString(R.string.setup_invalid)
                }
            }
        }

        box.addView(title)
        box.addView(input)
        box.addView(save)
        input.requestFocus()
        root.addView(box)
        exitMouseMode()
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
                            btn.style.cssText = 'margin-left:8px;padding:0 10px;cursor:pointer;font-size:1em;line-height:1;';
                            btn.addEventListener('click', function(e) {
                                e.preventDefault();
                                BRGVoice.onMicClicked();
                            });
                            q.insertAdjacentElement('afterend', btn);
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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
        // Arm long-press tracking for BACK. Many TV remotes never emit key-repeat
        // events, so timing repeats is unreliable — the framework's own long-press
        // timer (onKeyLongPress) is not.
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.repeatCount == 0) {
            event.startTracking()
            return true
        }
        // In pointer mode the D-pad steers the cursor instead of moving web focus.
        if (isDirectional(keyCode)) {
            if (mouseMode) {
                moveCursor(keyCode, event?.repeatCount ?: 0)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        // OK/ENTER: in pointer mode, click at the cursor. In normal mode, track
        // long-press (toggles pointer mode) and synthesize the click on key-up.
        if (isCenterKey(keyCode)) {
            if (mouseMode) {
                if (event?.repeatCount == 0) dispatchTap()
                return true
            }
            if (event?.repeatCount == 0 && webView != null) {
                event.startTracking()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        // Universal escape hatch back to setup, for remotes with no MENU button.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            backLongPressFired = true
            showSetup(prefill = serverUrl() ?: "")
            return true
        }
        // Holding OK toggles pointer mode (only over the web content).
        if (isCenterKey(keyCode) && webView != null && !voiceActive) {
            centerLongPressFired = true
            toggleMouseMode()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (backLongPressFired) {
                backLongPressFired = false
                return true
            }
            // BACK while listening cancels voice search, not page history.
            if (voiceActive) {
                speechRecognizer?.cancel()
                endVoiceSession()
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
        if (mouseMode && (isDirectional(keyCode) || isCenterKey(keyCode))) return true
        if (isCenterKey(keyCode)) {
            if (centerLongPressFired) {
                centerLongPressFired = false
                return true
            }
            // Normal mode: the DOWN was swallowed to track long-press — re-play
            // the press so the WebView clicks the currently focused element.
            val wv = webView
            if (wv != null && event != null) {
                val down = KeyEvent(event.downTime, event.eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
                val up = KeyEvent(event.downTime, event.eventTime, KeyEvent.ACTION_UP, keyCode, 0)
                wv.dispatchKeyEvent(down)
                wv.dispatchKeyEvent(up)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
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
        // Accelerate while the direction is held: base step grows with repeats.
        val step = cursorSizePx * 1.5f * (1f + (repeatCount.coerceAtMost(24) / 6f))
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> cursorY -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> cursorY += step
            KeyEvent.KEYCODE_DPAD_LEFT -> cursorX -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX += step
        }
        cursorX = cursorX.coerceIn(0f, (root.width - cursorSizePx).toFloat())
        cursorY = cursorY.coerceIn(0f, (root.height - cursorSizePx).toFloat())
        updateCursor()
        dispatchHover()
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
            MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
        )
    }

    private fun dispatchTap() {
        val wv = webView ?: return
        val (x, y) = cursorPoint()
        val now = SystemClock.uptimeMillis()
        wv.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        wv.dispatchTouchEvent(MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0))
    }

    /** Small circular cursor: white dot with a dark ring, visible on any background. */
    private inner class PointerView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
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

    // ----------------------------------------------------------------- voice

    /** Called from page JS when the injected mic button is clicked. */
    inner class VoiceSearchBridge {
        @JavascriptInterface
        fun onMicClicked() {
            runOnUiThread {
                if (voiceActive || System.currentTimeMillis() < voiceCooldownUntil) return@runOnUiThread
                startVoiceSearch()
            }
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
