package com.salp.mobilepc

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.URLEncoder

class BrowserActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var modeButton: Button
    private var desktopMode = true

    private val desktopUA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private val mobileUA =
        "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        webView = WebView(this)

        val back = Button(this).apply {
            text = "←"
            setOnClickListener { if (webView.canGoBack()) webView.goBack() else finish() }
        }
        val reload = Button(this).apply {
            text = "↻"
            setOnClickListener { webView.reload() }
        }

        address = EditText(this).apply {
            isSingleLine = true
            hint = "URL または検索"
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE) {
                    loadInput(text.toString())
                    true
                } else false
            }
        }

        val go = Button(this).apply {
            text = "Go"
            setOnClickListener { loadInput(address.text.toString()) }
        }

        modeButton = Button(this).apply {
            text = "PC"
            contentDescription = "PC表示とモバイル表示を切り替え"
            setOnClickListener { toggleDesktopMode() }
        }

        val external = Button(this).apply {
            text = "↗"
            contentDescription = "外部ブラウザで開く"
            setOnClickListener {
                val u = webView.url ?: address.text.toString().trim()
                if (u.isNotBlank()) openExternal(u)
            }
        }

        bar.addView(back)
        bar.addView(reload)
        bar.addView(address, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(go)
        bar.addView(modeButton)
        bar.addView(external)

        status = TextView(this).apply {
            text = "PC表示 / Ready"
            setPadding(12, 4, 12, 4)
        }

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.loadsImagesAutomatically = true
        s.blockNetworkLoads = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.defaultTextEncodingName = "UTF-8"
        s.userAgentString = desktopUA

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                val mode = if (desktopMode) "PC表示" else "Mobile表示"
                status.text = if (newProgress < 100) "$mode / Loading… $newProgress%" else "$mode / Ready"
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: return false
                return if (scheme == "http" || scheme == "https") {
                    false
                } else {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!url.isNullOrBlank()) address.setText(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank()) address.setText(url)
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val failed = request.url?.toString().orEmpty()
                    val code = error?.errorCode ?: -1
                    val desc = error?.description?.toString() ?: "unknown"
                    status.text = "WebView error $code: $desc"
                    Toast.makeText(
                        this@BrowserActivity,
                        "内蔵ブラウザで開けないため外部ブラウザで開きます",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (failed.isNotBlank()) openExternal(failed)
                }
            }
        }

        root.addView(bar)
        root.addView(status)
        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)

        loadInput(intent.getStringExtra("url") ?: "https://www.google.com")
    }

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        val s = webView.settings
        s.userAgentString = if (desktopMode) desktopUA else mobileUA
        s.useWideViewPort = desktopMode
        s.loadWithOverviewMode = desktopMode
        modeButton.text = if (desktopMode) "PC" else "M"
        val mode = if (desktopMode) "PC表示" else "Mobile表示"
        status.text = "$mode / Reloading…"
        webView.reload()
    }

    private fun normalizeInput(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        return when {
            s.startsWith("http://", true) || s.startsWith("https://", true) -> s
            s.contains(" ") -> "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
            s.contains(".") -> "https://$s"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
        }
    }

    private fun loadInput(raw: String) {
        val url = normalizeInput(raw)
        if (url.isBlank()) return
        address.setText(url)
        webView.loadUrl(url)
    }

    private fun openExternal(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "ブラウザを開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
