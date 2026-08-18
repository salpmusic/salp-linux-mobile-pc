package com.salp.mobilepc

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.setSupportZoom(true)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.startsWith("file:///android_asset/") == true) {
                    view?.evaluateJavascript(
                        """(function(){
                          var i=document.getElementById('url');
                          if(i && !i.dataset.enterReady){
                            i.dataset.enterReady='1';
                            i.addEventListener('keydown',function(e){
                              if(e.key==='Enter'){e.preventDefault();goUrl();}
                            });
                          }
                        })();""",
                        null
                    )
                }
            }
        }
        webView.addJavascriptInterface(AndroidBridge(), "Android")
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun openExternal(url: String) {
            runOnUiThread {
                val intent = Intent(this@MainActivity, BrowserActivity::class.java)
                intent.putExtra("url", url)
                startActivity(intent)
            }
        }
    }
}
