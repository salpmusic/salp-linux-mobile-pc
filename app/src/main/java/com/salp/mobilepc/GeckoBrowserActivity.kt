package com.salp.mobilepc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.net.URLEncoder

class GeckoBrowserActivity : AppCompatActivity() {
    private lateinit var geckoView: GeckoView
    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var modeButton: Button
    private var desktopMode = true
    private var canGoBackNow = false
    private var currentUrl = "https://www.google.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val back = Button(this).apply { text = "←"; setOnClickListener { if (canGoBackNow) session.goBack() else finish() } }
        val reload = Button(this).apply { text = "↻"; setOnClickListener { session.reload() } }
        address = EditText(this).apply {
            isSingleLine = true
            hint = "URL または検索"
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    loadInput(text.toString()); true
                } else false
            }
        }
        val go = Button(this).apply { text = "Go"; setOnClickListener { loadInput(address.text.toString()) } }
        modeButton = Button(this).apply { text = "Mobile"; setOnClickListener { toggleDisplayMode() } }
        val webFallback = Button(this).apply {
            text = "WEB"
            setOnClickListener {
                startActivity(Intent(this@GeckoBrowserActivity, BrowserActivity::class.java).putExtra("url", currentUrl))
            }
        }
        val external = Button(this).apply {
            text = "↗"
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))) }
                    .onFailure { Toast.makeText(this@GeckoBrowserActivity, "外部ブラウザを開けませんでした", Toast.LENGTH_SHORT).show() }
            }
        }
        bar.addView(back); bar.addView(reload)
        bar.addView(address, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(go); bar.addView(modeButton); bar.addView(webFallback); bar.addView(external)

        status = TextView(this).apply { text = "Firefox Gecko / PC表示"; setPadding(12,4,12,4) }
        geckoView = GeckoView(this)
        root.addView(bar); root.addView(status)
        root.addView(geckoView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        runtime = GeckoRuntime.create(this)
        val settings = GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
            .allowJavascript(true)
            .build()
        session = GeckoSession(settings)
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { canGoBackNow = canGoBack }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                currentUrl = url; address.setText(url)
                status.text = if (desktopMode) "Firefox Gecko / PC表示 / Loading…" else "Firefox Gecko / Mobile表示 / Loading…"
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                status.text = if (desktopMode) "Firefox Gecko / PC表示 / Ready" else "Firefox Gecko / Mobile表示 / Ready"
            }
        }
        session.open(runtime)
        geckoView.setSession(session)
        loadInput(intent.getStringExtra("url") ?: currentUrl)
    }

    private fun toggleDisplayMode() {
        desktopMode = !desktopMode
        if (desktopMode) {
            session.settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            session.settings.viewportMode = GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            modeButton.text = "Mobile"
            status.text = "Firefox Gecko / PC表示 / Reloading…"
        } else {
            session.settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            session.settings.viewportMode = GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            modeButton.text = "PC"
            status.text = "Firefox Gecko / Mobile表示 / Reloading…"
        }
        session.reload()
    }

    private fun normalizeInput(raw: String): String {
        val s = raw.trim(); if (s.isEmpty()) return ""
        return when {
            s.startsWith("http://", true) || s.startsWith("https://", true) -> s
            s.contains(" ") -> "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
            s.contains(".") -> "https://$s"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
        }
    }
    private fun loadInput(raw: String) {
        val url = normalizeInput(raw); if (url.isBlank()) return
        currentUrl = url; address.setText(url); session.loadUri(url)
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (canGoBackNow) session.goBack() else super.onBackPressed() }
    override fun onDestroy() { if (::session.isInitialized && session.isOpen) session.close(); super.onDestroy() }
}
