package com.salp.mobilepc

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import java.net.URLEncoder

class BrowserActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var address: EditText

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

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

        bar.addView(back)
        bar.addView(reload)
        bar.addView(address, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(go)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.setSupportZoom(true)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank()) address.setText(url)
            }
        }

        root.addView(bar)
        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)

        loadInput(intent.getStringExtra("url") ?: "https://www.google.com")
    }

    private fun loadInput(raw: String) {
        val s = raw.trim()
        if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://", true) || s.startsWith("https://", true) -> s
            s.contains(" ") -> "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
            s.contains(".") -> "https://$s"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
        }
        address.setText(url)
        webView.loadUrl(url)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
