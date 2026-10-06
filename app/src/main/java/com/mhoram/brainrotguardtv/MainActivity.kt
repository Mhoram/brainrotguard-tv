package com.mhoram.brainrotguardtv

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
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
            setTextColor(Color.WHITE)
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

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Keep everything (including YouTube embeds) inside the kiosk.
                return false
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
        box.addView(msg)
        box.addView(retry)
        retry.requestFocus()
        root.addView(box)
        webView?.destroy()
        webView = null
    }

    // ----------------------------------------------------------------- input

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // MENU (D-pad / remote menu button) reopens the server URL editor
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSetup(prefill = serverUrl() ?: "")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        // Stay in the kiosk: go back within the web app, otherwise do nothing
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack()
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
    }
}
