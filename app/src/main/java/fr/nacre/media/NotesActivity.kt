package fr.nacre.media

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Button
import android.widget.Toast
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A bundled, offline workspace. Only this trusted origin gets the export bridge. */
class NotesActivity : ComponentActivity() {
    private lateinit var browser: WebView
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var pendingPairCode: String? = null
    private var exportBytes: ByteArray? = null
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> chooser?.onReceiveValue(uri?.let { arrayOf(it) }); chooser = null }
    private val saveFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val bytes = exportBytes; exportBytes = null
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null && bytes != null) lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Destination inaccessible") }.isSuccess }
            Toast.makeText(this@NotesActivity, if(ok) "Fichier enregistré" else "Export impossible", Toast.LENGTH_LONG).show()
        }
    }
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPairCode = intent?.data?.takeIf { it.scheme == "echoall" && it.host == "notes" }?.getQueryParameter("code")?.takeIf { it.length < 100 }
        val theme = notesTheme(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.parseColor(theme.getString("bg"))); fitsSystemWindows = true }
        layout.addView(Button(this).apply { text = "‹ Echo-All  ·  Notes"; setTextColor(android.graphics.Color.parseColor(theme.getString("accent"))); setBackgroundColor(android.graphics.Color.parseColor(theme.getString("panel"))); setOnClickListener { closeAfterSave() } })
        browser = WebView(this)
        val loader = WebViewAssetLoader.Builder().setDomain("notes.echo-all.local").addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        browser.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        browser.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pendingPairCode?.let { code -> browser.evaluateJavascript("window.echoPendingPairCode=" + JSONObject.quote(code) + ";window.echoPairFromLink && window.echoPairFromLink(window.echoPendingPairCode)", null); pendingPairCode = null }
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                if(request.url.host == "notes.echo-all.local") {
                    val response = loader.shouldInterceptRequest(request.url)
                    if(response != null) {
                        if(request.url.path?.endsWith(".mjs") == true) response.mimeType = "text/javascript"
                        response.responseHeaders = mapOf("Content-Security-Policy" to "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self' blob:; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; font-src 'self' blob: data:; connect-src 'self' https:; object-src 'none'; frame-src 'none'; base-uri 'none'", "X-Content-Type-Options" to "nosniff")
                        return response
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(byteArrayOf()))
                }
                return if(request.url.scheme == "https") null else WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), java.io.ByteArrayInputStream(byteArrayOf()))
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean = request.url.host != "notes.echo-all.local"
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                chooser?.onReceiveValue(null); chooser = callback
                openFile.launch(if(params.acceptTypes.any { it.contains("json") }) arrayOf("application/json", "text/plain") else arrayOf("application/pdf"))
                return true
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                android.app.AlertDialog.Builder(this@NotesActivity).setMessage(message).setPositiveButton("Continuer") { _, _ -> result.confirm() }.setNegativeButton("Annuler") { _, _ -> result.cancel() }.setOnCancelListener { result.cancel() }.show()
                return true
            }
        }
        browser.addJavascriptInterface(object {
            @JavascriptInterface fun getTheme(): String = notesTheme(this@NotesActivity).toString()
            @JavascriptInterface fun lanRequest(id: String, address: String, packet: String) {
                if(!id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) return
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { NotesLanTransport.exchange(address, packet) }.getOrElse { JSONObject().put("error", "PC inaccessible. Vérifie le réseau et garde Notes ouvert sur le PC.") } }
                    browser.evaluateJavascript("window.echoLanResult && window.echoLanResult(" + JSONObject.quote(id) + "," + result.toString() + ")", null)
                }
            }
            @JavascriptInterface fun closeReady() { runOnUiThread { finish() } }
            @JavascriptInterface fun saveFile(name: String, mime: String, base64: String) {
                if(mime !in listOf("application/pdf", "application/json") || base64.length > 112 * 1024 * 1024) return
                lifecycleScope.launch {
                    if(exportBytes != null) return@launch
                    val bytes = withContext(Dispatchers.Default) { runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() } ?: return@launch
                    exportBytes = bytes
                    saveFile.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180)))
                }
            }
        }, "EchoNotes")
        layout.addView(browser, LinearLayout.LayoutParams(-1,0,1f)); setContentView(layout)
        browser.loadUrl("https://notes.echo-all.local/notes/index.html")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { closeAfterSave() } })
    }
    private fun closeAfterSave() { browser.evaluateJavascript("if(window.echoNotesFlush){window.echoNotesFlush().then(ok=>{if(ok)EchoNotes.closeReady();});}else{EchoNotes.closeReady();}", null) }
    override fun onPause() { if(::browser.isInitialized) browser.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); if(::browser.isInitialized) browser.onResume() }
    override fun onDestroy() { chooser?.onReceiveValue(null); if(::browser.isInitialized) { browser.removeJavascriptInterface("EchoNotes"); browser.destroy() }; super.onDestroy() }
}
