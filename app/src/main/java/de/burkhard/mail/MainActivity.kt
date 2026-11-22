package de.burkhard.mail

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.DragEvent
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    lateinit var web: WebView
    private var pageReady = false
    private val pendingJs = mutableListOf<String>()
    private var stoppedAt = 0L
    private var unlocked = false

    private val mailReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val j = i.getStringExtra("json") ?: return
            js("window.__newMail&&window.__newMail($j)")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecure()
        web = WebView(this)
        setContentView(web)
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(85, 200)
        }
        web.addJavascriptInterface(Bridge(this), "MailBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(req.url)
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingJs.forEach { web.evaluateJavascript(it, null) }; pendingJs.clear()
                handleIntent(intent)
            }
        }
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")

        // Text aus einer anderen App hineinziehen (geteilter Bildschirm / schwebendes Fenster)
        web.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> e.clipDescription?.hasMimeType("text/*") == true
                DragEvent.ACTION_DROP -> {
                    val clip = e.clipData
                    val sb = StringBuilder()
                    if (clip != null) for (k in 0 until clip.itemCount) {
                        val t = clip.getItemAt(k).coerceToText(this)?.toString()
                        if (!t.isNullOrBlank()) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(t) }
                    }
                    if (sb.isNotEmpty()) {
                        val d = resources.displayMetrics.density
                        js("window.__dropText&&window.__dropText(${JSONObject.quote(sb.toString())},${e.x / d},${e.y / d})")
                        true
                    } else false
                }
                else -> true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__back?window.__back():false") { r -> if (r != "true") { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true } }
            }
        })

        ContextCompat.registerReceiver(this, mailReceiver, IntentFilter(MailService.ACTION_NEW_MAIL), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        MailService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (pageReady) handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("openMail")?.let { js("window.__openMail&&window.__openMail($it)"); i.removeExtra("openMail") }
        if (i.action == Intent.ACTION_SENDTO) i.data?.let { u: Uri ->
            val to = u.schemeSpecificPart.substringBefore('?')
            js("window.__compose&&window.__compose(${JSONObject.quote(to)})"); i.action = null
        }
        // „Teilen“ → Mail: geteilter Text wird eine neue E-Mail
        if (i.action == Intent.ACTION_SEND) {
            val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            val subject = i.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            if (text.isNotBlank() || subject.isNotBlank())
                js("window.__shareText&&window.__shareText(${JSONObject.quote(subject)},${JSONObject.quote(text)})")
            i.action = null
        }
    }

    override fun onStop() { super.onStop(); MailService.App.foreground = false; stoppedAt = SystemClock.elapsedRealtime() }

    override fun onStart() {
        super.onStart()
        MailService.App.foreground = true
        Overlay.close(this)
        if (skipLockOnce) { skipLockOnce = false; return }
        val lock = MailApp.app.store.load().getJSONObject("settings").optJSONObject("lock")
        val on = lock?.optBoolean("on", true) ?: true
        val after = (lock?.optInt("after", 1) ?: 1) * 60_000L
        if (on && unlocked && stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt >= after) {
            unlocked = false
            js("window.__relock&&window.__relock()")
        }
    }

    override fun onDestroy() { try { unregisterReceiver(mailReceiver) } catch (_: Exception) {}; super.onDestroy() }

    fun applySecure() {
        val allow = MailApp.app.store.load().getJSONObject("settings").optBoolean("screenshots", true)
        if (allow) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun js(code: String) = runOnUiThread { if (pageReady) web.evaluateJavascript(code, null) else pendingJs += code }

    fun reply(id: String, ok: Boolean, payload: JSONObject) {
        js("window.__reply(${JSONObject.quote(id)},$ok,${JSONObject.quote(payload.toString())})")
    }

    fun toastJs(text: String) = js("window.toast&&toast(${JSONObject.quote(text)})")

    // ---------- Fingerabdruck ----------
    fun askBiometric() {
        val bm = BiometricManager.from(this)
        val auth = if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                   else BiometricManager.Authenticators.BIOMETRIC_WEAK
        if (bm.canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            // Kein Fingerabdruck und keine Displaysperre eingerichtet: nicht aussperren
            unlocked = true; js("window.__unlocked&&window.__unlocked()"); return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlocked = true; js("window.__unlocked&&window.__unlocked()")
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder().setTitle("Mail entsperren").setSubtitle("Mit Fingerabdruck oder Handy-PIN")
            .setAllowedAuthenticators(auth).apply { if (Build.VERSION.SDK_INT < 30) setNegativeButtonText("Abbrechen") }.build()
        prompt.authenticate(info)
    }

    // ---------- Datei auswählen (Sicherung wiederherstellen) ----------
    /** Beim Zurückkommen aus Dateiauswahl oder Teilen-Menü nicht sperren. */
    @Volatile var skipLockOnce = false
    private var pickLatch: CountDownLatch? = null
    private var pickResult: Uri? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pickResult = uri; pickLatch?.countDown() }

    private var multiLatch: CountDownLatch? = null
    private var multiResult: List<Uri> = emptyList()
    private val multiPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> multiResult = uris ?: emptyList(); multiLatch?.countDown() }

    /** Dateien zum Anhängen auswählen (mehrere möglich). */
    fun pickFilesBlocking(): List<Uri> {
        val l = CountDownLatch(1); multiLatch = l; multiResult = emptyList()
        skipLockOnce = true
        runOnUiThread { try { multiPicker.launch(arrayOf("*/*")) } catch (_: Exception) { l.countDown() } }
        l.await(600, TimeUnit.SECONDS)
        return multiResult
    }

    fun pickFileBlocking(): Uri? {
        val l = CountDownLatch(1); pickLatch = l; pickResult = null
        skipLockOnce = true
        runOnUiThread { try { picker.launch(arrayOf("*/*")) } catch (_: Exception) { l.countDown() } }
        l.await(600, TimeUnit.SECONDS)
        return pickResult
    }

    // ---------- Berechtigungen (vom Hintergrund-Thread aus) ----------
    private var permLatch: CountDownLatch? = null
    private var permResult = false

    fun requestPermissionBlocking(p: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) return true
        val l = CountDownLatch(1); permLatch = l; permResult = false
        runOnUiThread { requestPermissions(arrayOf(p), 22) }
        l.await(120, TimeUnit.SECONDS)
        return permResult
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 22) { permResult = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED; permLatch?.countDown() }
    }
}
