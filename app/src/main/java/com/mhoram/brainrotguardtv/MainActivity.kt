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
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
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
    private var voiceActive = false
    private var voiceReady = false
    private var voiceCooldownUntil = 0L

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
                // Hook the search box: focusing it opens the voice dialog.
                // Guarded per page load; the bridge is one-way (opens a dialog
                // only) so exposure to iframes is a mild annoyance at worst.
                view.evaluateJavascript(
                    """
                    (function() {
                        if (window.__brgVoiceHookInstalled) return;
                        window.__brgVoiceHookInstalled = true;
                        document.querySelectorAll('form[action="/search"] input[name="q"]').forEach(function(el) {
                            el.addEventListener('focus', function() { BRGVoice.onSearchFocused(); });
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
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        // Universal escape hatch back to setup, for remotes with no MENU button.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            backLongPressFired = true
            showSetup(prefill = serverUrl() ?: "")
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
        return super.onKeyUp(keyCode, event)
    }

    // ----------------------------------------------------------------- voice

    /** Called from page JS when the search input gains focus. */
    inner class VoiceSearchBridge {
        @JavascriptInterface
        fun onSearchFocused() {
            runOnUiThread {
                if (voiceActive || System.currentTimeMillis() < voiceCooldownUntil) return@runOnUiThread
                startVoiceSearch()
            }
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceOverlay: TextView? = null

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
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        })
        // Watchdog: if the service never reports ready, fail cleanly instead of
        // hanging on the overlay forever.
        overlay.postDelayed({
            if (voiceActive && !voiceReady && speechRecognizer === recognizer) {
                endVoiceSession()
                Toast.makeText(this, getString(R.string.voice_unavailable), Toast.LENGTH_LONG).show()
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
