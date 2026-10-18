package com.salp.mobilepc

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Desktop shell (assets/index.html) + Files/Editor bridge.
 *
 * Security model:
 *  - The JS bridge only answers while the WebView shows the bundled page (file:///android_asset/).
 *    Any other navigation inside this WebView is cancelled and handed to GeckoBrowserActivity.
 *  - JS never sees or passes content:// URIs. Kotlin keeps URIs in a token table; JS only gets
 *    opaque tokens for folder entries and the Editor's current file is held on the Kotlin side.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("salp_files", MODE_PRIVATE) }

    @Volatile private var trustedPage = false

    // ---- Kotlin-managed file state ----
    private data class Entry(val tree: Uri, val docId: String, val name: String, val mime: String, val dir: Boolean)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val tokenSeq = AtomicInteger(0)
    @Volatile private var currentTree: Uri? = null
    @Volatile private var editorUri: Uri? = null
    @Volatile private var editorName: String = "untitled.txt"
    @Volatile private var pendingSaveText: String? = null

    companion object {
        const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        private const val ASSET_PREFIX = "file:///android_asset/"
        private const val PREF_LAST_TREE = "last_tree"
        private val TEXT_EXT = setOf(
            "txt", "md", "markdown", "json", "js", "mjs", "ts", "html", "htm", "css", "kt", "kts",
            "java", "xml", "csv", "tsv", "log", "ini", "cfg", "conf", "yml", "yaml", "toml", "sh",
            "py", "c", "h", "cpp", "gradle", "properties", "sql", "srt", "vtt", "gitignore"
        )
        private val TEXT_MIME = setOf(
            "application/json", "application/xml", "application/javascript", "application/x-javascript",
            "application/x-sh", "application/x-yaml", "application/yaml", "application/toml",
            "application/sql", "application/x-subrip"
        )
    }

    // ---- Activity Result API ----
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { js("salpFiles.onPickCancelled", JSONObject()); return@registerForActivityResult }
        val persisted = persist(uri)
        if (persisted) prefs.edit().putString(PREF_LAST_TREE, uri.toString()).apply()
        else toast("権限を保持できませんでした（次回起動時は再選択が必要です）")
        currentTree = uri
        entries.clear()
        io.execute { js("salpFiles.onFolder", rootInfo(uri)) }
    }

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        persist(uri) // optional: temporary grant is enough for this session
        loadTextAsync(uri, null)
    }

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingSaveText
        pendingSaveText = null
        if (uri == null || text == null) {
            js("salpEditor.onSaved", JSONObject().put("ok", false).put("cancelled", true).put("error", "保存をキャンセルしました"))
            return@registerForActivityResult
        }
        persist(uri)
        saveTextAsync(uri, text, fromSaveAs = true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this); setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = false
        webView.settings.setSupportZoom(true)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                handleNavigation(request?.url?.toString())
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = handleNavigation(url)
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                trustedPage = url?.startsWith(ASSET_PREFIX) == true
                super.onPageStarted(view, url, favicon)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                trustedPage = url?.startsWith(ASSET_PREFIX) == true
                if (trustedPage) {
                    view?.evaluateJavascript("""(function(){var i=document.getElementById('url');if(i&&!i.dataset.enterReady){i.dataset.enterReady='1';i.addEventListener('keydown',function(e){if(e.key==='Enter'){e.preventDefault();goUrl();}});}})();""", null)
                }
            }
        }
        val bridge = AndroidBridge()
        webView.addJavascriptInterface(bridge, "Android")       // existing name (openExternal)
        webView.addJavascriptInterface(bridge, "AndroidBridge") // v0.3.0 name
        webView.loadUrl(ASSET_PREFIX + "index.html")
    }

    /** Keep this WebView on the bundled desktop; external http(s) links go to the Gecko browser. */
    private fun handleNavigation(url: String?): Boolean {
        if (url == null || url.startsWith(ASSET_PREFIX)) return false
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            startActivity(Intent(this, GeckoBrowserActivity::class.java).putExtra("url", url))
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (webView.canGoBack()) webView.goBack() else super.onBackPressed() }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }

    // ---- helpers ----
    private fun js(fn: String, payload: JSONObject) {
        val script = "try{window.$fn&&window.$fn($payload)}catch(e){console.error(e)}"
        runOnUiThread { if (!isFinishing && !isDestroyed && trustedPage) webView.evaluateJavascript(script, null) }
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    /** Returns true only if a persistable permission was actually taken. */
    private fun persist(uri: Uri): Boolean {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try { contentResolver.takePersistableUriPermission(uri, rw); true } catch (_: Exception) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true } catch (_: Exception) { false }
        }
    }

    private fun newToken(e: Entry): String {
        val t = "e" + tokenSeq.incrementAndGet()
        entries[t] = e
        return t
    }

    private fun rootInfo(tree: Uri): JSONObject {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val name = queryName(DocumentsContract.buildDocumentUriUsingTree(tree, rootId)) ?: "Folder"
        val token = newToken(Entry(tree, rootId, name, DocumentsContract.Document.MIME_TYPE_DIR, true))
        return JSONObject().put("ok", true).put("token", token).put("name", name)
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun querySize(uri: Uri): Long = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    } catch (_: Exception) { -1L }

    private fun isText(name: String, mime: String?): Boolean {
        val m = mime?.lowercase() ?: ""
        if (m.startsWith("text/") || m in TEXT_MIME) return true
        return name.substringAfterLast('.', "").lowercase() in TEXT_EXT
    }

    private fun guessMime(name: String, mime: String?): String {
        if (!mime.isNullOrBlank() && mime != "application/octet-stream") return mime
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: (mime?.ifBlank { null } ?: "*/*")
    }

    private fun readLimited(uri: Uri): ByteArray =
        contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(chunk); if (n < 0) break
                total += n
                if (total > MAX_TEXT_BYTES) throw IllegalStateException("ファイルが大きすぎます。Editorで開ける上限は2MBです。")
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        } ?: throw IllegalStateException("ファイルを開けませんでした")

    private fun canWrite(uri: Uri): Boolean = try {
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0 else true
        } ?: true
    } catch (_: Exception) { true }

    private fun loadTextAsync(uri: Uri, knownName: String?) {
        io.execute {
            val name = knownName ?: queryName(uri) ?: "untitled.txt"
            val out = JSONObject().put("name", name)
            try {
                val size = querySize(uri) // checked before reading
                if (size > MAX_TEXT_BYTES) {
                    out.put("ok", false).put("error", "ファイルが大きすぎます (${size / 1024} KB)。Editorで開ける上限は2MBです。")
                } else {
                    var text = String(readLimited(uri), Charsets.UTF_8)
                    if (text.startsWith("\uFEFF")) text = text.substring(1)
                    editorUri = uri
                    editorName = name
                    out.put("ok", true).put("text", text).put("writable", canWrite(uri))
                }
            } catch (e: SecurityException) {
                out.put("ok", false).put("error", "アクセス権がありません。もう一度ファイルを選択してください。")
            } catch (e: Exception) {
                out.put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            js("salpEditor.onLoaded", out)
        }
    }

    /** Overwrite with "wt", then read back and verify. On failure suggest Save As. */
    private fun saveTextAsync(uri: Uri, text: String, fromSaveAs: Boolean) {
        io.execute {
            val out = JSONObject()
            try {
                val bytes = text.toByteArray(Charsets.UTF_8)
                val stream = try { contentResolver.openOutputStream(uri, "wt") } catch (e: UnsupportedOperationException) { null }
                    catch (e: IllegalArgumentException) { null }
                    ?: throw IllegalStateException("このファイルは上書き保存（wt）に対応していません")
                stream.use { it.write(bytes); it.flush() }
                val back = try { readLimited(uri) } catch (_: Exception) { null }
                if (back != null && !back.contentEquals(bytes)) {
                    throw IllegalStateException("保存後の内容が一致しませんでした")
                }
                editorUri = uri
                editorName = queryName(uri) ?: editorName
                out.put("ok", true).put("name", editorName).put("bytes", bytes.size).put("verified", back != null)
                toast(if (back != null) "保存しました（確認済み）" else "保存しました")
            } catch (e: Exception) {
                val msg = if (e is SecurityException) "書き込み権限がありません" else (e.message ?: e.javaClass.simpleName)
                out.put("ok", false).put("error", msg).put("suggestSaveAs", !fromSaveAs)
                toast(if (fromSaveAs) "保存できませんでした" else "保存できませんでした。「名前を付けて保存」をお試しください")
            }
            js("salpEditor.onSaved", out)
        }
    }

    /**
     * Methods exposed to the bundled page only. Every method checks [trustedPage];
     * calls from any other page are ignored.
     */
    inner class AndroidBridge {
        private fun ok() = trustedPage

        @JavascriptInterface fun openExternal(url: String) {
            if (!ok()) return
            if (!(url.startsWith("http://", true) || url.startsWith("https://", true))) return
            runOnUiThread { startActivity(Intent(this@MainActivity, GeckoBrowserActivity::class.java).putExtra("url", url)) }
        }

        @JavascriptInterface fun toast(msg: String) { if (ok()) this@MainActivity.toast(msg.take(200)) }

        // ---- Files ----
        @JavascriptInterface fun pickFolder() {
            if (!ok()) return
            runOnUiThread { try { pickTree.launch(null) } catch (e: ActivityNotFoundException) { this@MainActivity.toast("フォルダ選択を開けませんでした") } }
        }

        /** Restores the last folder if its persisted permission is still held; otherwise reports needPick. */
        @JavascriptInterface fun restoreLastFolder() {
            if (!ok()) return
            io.execute {
                val s = prefs.getString(PREF_LAST_TREE, null)
                val held = s != null && contentResolver.persistedUriPermissions.any { it.uri.toString() == s && it.isReadPermission }
                if (!held) {
                    if (s != null) prefs.edit().remove(PREF_LAST_TREE).apply()
                    js("salpFiles.onFolder", JSONObject().put("ok", false).put("needPick", true).put("hadFolder", s != null))
                    return@execute
                }
                try {
                    val tree = Uri.parse(s!!)
                    currentTree = tree
                    entries.clear()
                    js("salpFiles.onFolder", rootInfo(tree))
                } catch (_: Exception) {
                    js("salpFiles.onFolder", JSONObject().put("ok", false).put("needPick", true).put("hadFolder", true))
                }
            }
        }

        @JavascriptInterface fun listDir(token: String) {
            if (!ok()) return
            io.execute {
                val out = JSONObject().put("token", token)
                val dir = entries[token]
                if (dir == null || !dir.dir || dir.tree != currentTree) {
                    out.put("ok", false).put("needPick", true).put("error", "フォルダ情報が古くなりました。もう一度フォルダを開いてください。")
                    js("salpFiles.onList", out); return@execute
                }
                try {
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(dir.tree, dir.docId)
                    val cols = arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    )
                    val items = JSONArray()
                    contentResolver.query(children, cols, null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val id: String? = c.getString(0)
                            if (id != null) {
                            val mime = if (c.isNull(2)) "" else c.getString(2)
                            val name = if (c.isNull(1)) "(no name)" else c.getString(1)
                            val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                            val t = newToken(Entry(dir.tree, id, name, mime, isDir))
                            items.put(JSONObject()
                                .put("token", t)
                                .put("name", name)
                                .put("mime", mime)
                                .put("dir", isDir)
                                .put("size", if (c.isNull(3)) -1L else c.getLong(3))
                                .put("modified", if (c.isNull(4)) 0L else c.getLong(4))
                                .put("text", !isDir && isText(name, mime)))
                            }
                        }
                    } ?: throw IllegalStateException("フォルダを読み込めませんでした")
                    out.put("ok", true).put("items", items)
                } catch (e: SecurityException) {
                    out.put("ok", false).put("needPick", true).put("error", "アクセス権がありません。もう一度フォルダを選択してください。")
                } catch (e: Exception) {
                    out.put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
                }
                js("salpFiles.onList", out)
            }
        }

        @JavascriptInterface fun openEntry(token: String) {
            if (!ok()) return
            val e = entries[token]
            if (e == null || e.dir || e.tree != currentTree) { this@MainActivity.toast("ファイル情報が古くなりました。一覧を更新してください"); return }
            val uri = DocumentsContract.buildDocumentUriUsingTree(e.tree, e.docId)
            if (isText(e.name, e.mime)) { loadTextAsync(uri, e.name); return }
            runOnUiThread {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, guessMime(e.name, e.mime))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { startActivity(intent) } catch (ex: ActivityNotFoundException) {
                    this@MainActivity.toast("このファイルを開けるアプリがありません")
                } catch (ex: Exception) {
                    this@MainActivity.toast("ファイルを開けませんでした")
                }
            }
        }

        // ---- Editor (current file URI is held in Kotlin) ----
        @JavascriptInterface fun editorOpen() {
            if (!ok()) return
            runOnUiThread { try { openDoc.launch(arrayOf("text/*", "application/json", "application/xml", "application/javascript", "*/*")) } catch (e: ActivityNotFoundException) { this@MainActivity.toast("ファイル選択を開けませんでした") } }
        }

        @JavascriptInterface fun editorNew() { if (ok()) { editorUri = null; editorName = "untitled.txt" } }

        @JavascriptInterface fun editorHasFile(): Boolean = ok() && editorUri != null

        @JavascriptInterface fun editorSave(text: String) {
            if (!ok()) return
            val uri = editorUri
            if (uri == null) { editorSaveAs(editorName, text); return }
            saveTextAsync(uri, text, fromSaveAs = false)
        }

        @JavascriptInterface fun editorSaveAs(suggestedName: String, text: String) {
            if (!ok()) return
            pendingSaveText = text
            val name = suggestedName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "untitled.txt" }
            runOnUiThread { try { createDoc.launch(name) } catch (e: ActivityNotFoundException) { pendingSaveText = null; this@MainActivity.toast("保存先を選択できませんでした") } }
        }
    }
}
