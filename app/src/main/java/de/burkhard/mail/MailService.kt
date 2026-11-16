package de.burkhard.mail

import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sun.mail.imap.IMAPFolder
import org.json.JSONObject
import java.util.Date
import javax.mail.Folder
import javax.mail.Message
import javax.mail.event.MessageCountAdapter
import javax.mail.event.MessageCountEvent
import javax.mail.internet.InternetAddress

/**
 * Hält im Hintergrund die Verbindung zu allen Konten offen (IMAP IDLE).
 * Neue Mails werden in Sekunden gemeldet; die Abwesenheitsnotiz wird hier verschickt.
 */
class MailService : Service() {
    private val threads = mutableListOf<Thread>()
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NotificationCompat.Builder(this, MailApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_mail).setContentTitle("Mail ist verbunden")
            .setContentText("Neue E-Mails werden sofort gemeldet").setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
        restartWatchers()
        return START_STICKY
    }

    private fun restartWatchers() {
        running = false
        threads.forEach { it.interrupt() }
        threads.clear()
        val app = MailApp.app
        val cfg = app.store.load()
        if (cfg.getJSONObject("settings").optJSONObject("notify")?.optBoolean("instant", true) == false || app.store.accounts().isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        running = true
        for (acc in app.store.accounts()) {
            val t = Thread({ watch(acc) }, "idle-" + acc.optString("id"))
            t.isDaemon = true; t.start(); threads += t
        }
    }

    private fun watch(acc: JSONObject) {
        val app = MailApp.app
        var backoff = 5_000L
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                val st = app.core.connectNew(acc, app.store.password(acc.getString("id")))
                try {
                    val inbox = st.getFolder("INBOX") as IMAPFolder
                    inbox.open(Folder.READ_ONLY)
                    inbox.addMessageCountListener(object : MessageCountAdapter() {
                        override fun messagesAdded(e: MessageCountEvent) {
                            for (m in e.messages) try { onNewMail(acc, inbox, m) } catch (_: Exception) {}
                        }
                    })
                    backoff = 5_000L
                    var since = System.currentTimeMillis()
                    while (running && !Thread.currentThread().isInterrupted && st.isConnected) {
                        inbox.idle(true)
                        if (System.currentTimeMillis() - since > 20 * 60_000) { inbox.messageCount; since = System.currentTimeMillis() }
                    }
                } finally { try { st.close() } catch (_: Exception) {} }
            } catch (_: InterruptedException) { return } catch (_: Exception) {
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }
                backoff = minOf(backoff * 2, 5 * 60_000L)
            }
        }
    }

    private fun onNewMail(acc: JSONObject, inbox: IMAPFolder, m: Message) {
        val app = MailApp.app
        val cfg = app.store.load()
        val rules = cfg.getJSONObject("rules")
        val s = cfg.getJSONObject("settings")
        val fr = m.from?.firstOrNull() as? InternetAddress
        val fromAddr = fr?.address ?: ""
        val fromName = fr?.personal ?: fromAddr
        val subject = m.subject ?: ""
        val auth = (m.getHeader("Authentication-Results") ?: emptyArray()).joinToString(" ")
        val sc = Spam.score(fromName, fromAddr, (m.replyTo?.firstOrNull() as? InternetAddress)?.address, subject, auth, rules)
        val spammy = sc.blocked || (!sc.allowed && sc.score >= s.optInt("spamThreshold", 4))
        val text = try { (m as com.sun.mail.imap.IMAPMessage).peek = true; plain(m) } catch (_: Exception) { "" }
        val n = JSONObject().put("acc", acc.getString("id")).put("path", "INBOX").put("uid", inbox.getUID(m))
            .put("from", fromName).put("fromAddr", fromAddr).put("subject", subject).put("text", text.take(600))
        if (!sc.blocked) {
            val i = Intent(ACTION_NEW_MAIL).setPackage(packageName).putExtra("json", n.toString())
            sendBroadcast(i)
            if (!App.foreground) showNotification(this, n)
        }
        // Abwesenheitsnotiz
        val away = s.optJSONObject("away")
        if (away != null && away.optBoolean("on") && awayActive(away) && away.optJSONArray("accounts")?.strings()?.contains(acc.getString("id")) == true && fromAddr.isNotBlank()) {
            val isList = m.getHeader("List-Id") != null || m.getHeader("List-Unsubscribe") != null ||
                (m.getHeader("Auto-Submitted")?.firstOrNull()?.lowercase()?.startsWith("auto") == true) ||
                (m.getHeader("Precedence")?.firstOrNull()?.lowercase() in listOf("bulk", "list", "junk")) ||
                Regex("no-?reply|mailer-daemon|postmaster", RegexOption.IGNORE_CASE).containsMatchIn(fromAddr)
            if (away.optBoolean("skipLists", true) && (isList || spammy)) return
            if (acc.optString("email").equals(fromAddr, true)) return
            val key = (away.optString("from") + "|" + fromAddr.lowercase())
            val done = app.store.getJson("away_done")
            if (away.optBoolean("once", true) && done.has(key)) return
            val html = away.optString("text")
            val plainTxt = html.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "")
            val d = JSONObject().put("acc", acc.getString("id")).put("to", fromAddr)
                .put("subject", away.optString("subject", "Abwesenheitsnotiz") + ": " + subject)
                .put("text", plainTxt).put("html", html)
                .put("inReplyTo", (m as javax.mail.internet.MimeMessage).messageID ?: "")
            try { app.core.send(d, false); done.put(key, Date().time); app.store.putJson("away_done", done) } catch (_: Exception) {}
        }
    }

    private fun awayActive(a: JSONObject): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.GERMANY).format(Date())
        val f = a.optString("from"); val t = a.optString("to")
        return (f.isBlank() || f <= today) && (t.isBlank() || t >= today)
    }

    private fun plain(p: javax.mail.Part, depth: Int = 0): String {
        if (depth > 5) return ""
        return when {
            p.isMimeType("text/plain") -> p.content as? String ?: ""
            p.isMimeType("text/html") -> ((p.content as? String) ?: "").replace(Regex("(?is)<(style|script)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ")
            p.isMimeType("multipart/*") -> {
                val mp = p.content as javax.mail.Multipart
                var best = ""
                for (i in 0 until mp.count) { val bp = mp.getBodyPart(i); if (bp.isMimeType("text/plain")) return plain(bp, depth + 1); if (best.isEmpty()) best = plain(bp, depth + 1) }
                best
            }
            else -> ""
        }
    }

    override fun onDestroy() { running = false; threads.forEach { it.interrupt() }; super.onDestroy() }

    object App { @Volatile var foreground = false }

    companion object {
        const val ACTION_NEW_MAIL = "de.burkhard.mail.NEW_MAIL"
        private val CODE_RX = Regex("(?:code|Code|CODE|Bestätigungscode|Sicherheitscode|PIN|TAN|verification|Verifizierung)[^0-9]{0,40}(\\d{3}[\\s-]?\\d{3}|\\d{4,8})")
        private val SIX = Regex("\\b(\\d{6})\\b")

        fun findCode(t: String): String? = (CODE_RX.find(t)?.groupValues?.get(1) ?: SIX.find(t)?.groupValues?.get(1))?.replace(Regex("[\\s-]"), "")

        fun start(ctx: Context) {
            try {
                val app = MailApp.app
                if (app.store.accounts().isEmpty()) return
                if (app.store.load().getJSONObject("settings").optJSONObject("notify")?.optBoolean("instant", true) == false) { ctx.stopService(Intent(ctx, MailService::class.java)); return }
                ContextCompat.startForegroundService(ctx, Intent(ctx, MailService::class.java))
            } catch (_: Exception) {}
        }

        fun reload(ctx: Context) {
            val app = MailApp.app
            if (app.store.accounts().isEmpty() || app.store.load().getJSONObject("settings").optJSONObject("notify")?.optBoolean("instant", true) == false)
                ctx.stopService(Intent(ctx, MailService::class.java))
            else start(ctx)
        }

        /** Zeigt eine neue Mail: als Fenster über anderen Apps (wenn erlaubt) oder als Benachrichtigung. */
        fun showNotification(ctx: Context, n: JSONObject, forceSystem: Boolean = false) {
            val s = MailApp.app.store.load().getJSONObject("settings").optJSONObject("notify") ?: JSONObject()
            val code = if (s.optBoolean("code", true)) findCode(n.optString("subject") + " " + n.optString("text")) else null
            val nid = (n.optLong("uid") % 100000).toInt() + 100
            if (!forceSystem && s.optBoolean("popup", true) && Settings.canDrawOverlays(ctx)) {
                Handler(Looper.getMainLooper()).post { Overlay.show(ctx, n, code, s.optBoolean("preview", true)) }
                return
            }
            val open = Intent(ctx, MainActivity::class.java).putExtra("openMail", n.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val b = NotificationCompat.Builder(ctx, MailApp.CH_MAIL)
                .setSmallIcon(R.drawable.ic_stat_mail).setColor(0xFF2F86E6.toInt())
                .setContentTitle(n.optString("from")).setContentText(n.optString("subject"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(n.optString("subject") + (if (s.optBoolean("preview", true)) "\n" + n.optString("text").take(300) else "")))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(ctx, nid, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            if (!s.optBoolean("sound", true)) b.setSilent(true) else b.setDefaults(NotificationCompat.DEFAULT_ALL)
            if (code != null) {
                val ci = Intent(ctx, NotifyActionReceiver::class.java).setAction(NotifyActionReceiver.ACTION_COPY).putExtra("text", code).putExtra("nid", nid).putExtra("close", true)
                b.addAction(0, "Code $code kopieren", PendingIntent.getBroadcast(ctx, nid * 3, ci, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            b.addAction(0, "Öffnen", PendingIntent.getActivity(ctx, nid * 3 + 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            val cl = Intent(ctx, NotifyActionReceiver::class.java).setAction(NotifyActionReceiver.ACTION_CLOSE).putExtra("nid", nid)
            b.addAction(0, "Schließen", PendingIntent.getBroadcast(ctx, nid * 3 + 2, cl, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            try { NotificationManagerCompat.from(ctx).notify(nid, b.build()) } catch (_: SecurityException) {}
        }
    }
}

/** Fenster über anderen Apps: Absender, Betreff, markierbarer Text, Code kopieren, Öffnen, Schließen. */
object Overlay {
    private var view: View? = null

    fun show(ctx: Context, n: JSONObject, code: String?, preview: Boolean) {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        close(ctx)
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt() }
        val dark = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val fg = if (dark) Color.parseColor("#E3EFF1") else Color.parseColor("#12262D")
        val muted = if (dark) Color.parseColor("#8FA9B1") else Color.parseColor("#557079")
        val accent = Color.parseColor("#2F86E6")
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(if (dark) Color.parseColor("#15292F") else Color.parseColor("#F4F9FA")); setStroke(dp(1), if (dark) 0x33FFFFFF else 0xCCFFFFFF.toInt()) }
            elevation = dp(12).toFloat()
        }
        fun tv(t: String, size: Float, color: Int, bold: Boolean = false, selectable: Boolean = false) = TextView(ctx).apply {
            text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size); if (bold) typeface = Typeface.DEFAULT_BOLD
            if (selectable) setTextIsSelectable(true)
        }
        fun btn(t: String, primary: Boolean, click: () -> Unit) = Button(ctx).apply {
            text = t; isAllCaps = false; setTextColor(if (primary) Color.WHITE else fg)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                if (primary) intArrayOf(Color.parseColor("#5AA6F5"), accent) else if (dark) intArrayOf(Color.parseColor("#3E5878"), Color.parseColor("#16263E")) else intArrayOf(Color.WHITE, Color.parseColor("#D6E3F4"))).apply { cornerRadius = dp(14).toFloat() }
            setOnClickListener { click() }
        }
        box.addView(tv("Mail · neue E-Mail", 12f, muted))
        box.addView(tv(n.optString("from"), 16f, fg, bold = true))
        box.addView(tv(n.optString("subject"), 14f, fg, bold = true))
        if (preview) box.addView(tv(n.optString("text").take(300), 14f, fg, selectable = true).apply { setPadding(0, dp(6), 0, 0) })
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (code != null) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
            row.addView(tv(code, 24f, fg, bold = true, selectable = true).apply { letterSpacing = 0.15f }, LinearLayout.LayoutParams(0, -2, 1f))
            val copyBtn = btn("Code kopieren", true) {}
            copyBtn.setOnClickListener { cm.setPrimaryClip(ClipData.newPlainText("Code", code)); copyBtn.text = "Kopiert ✓" }
            row.addView(copyBtn)
            box.addView(row)
        }
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        actions.addView(btn("Schließen", false) { close(ctx) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        actions.addView(btn("Öffnen", true) {
            close(ctx)
            ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("openMail", n.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        box.addView(actions)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; y = dp(40); horizontalMargin = 0.03f }
        val frame = LinearLayout(ctx).apply { setPadding(dp(10), 0, dp(10), 0); addView(box, LinearLayout.LayoutParams(-1, -2)) }
        try { wm.addView(frame, lp); view = frame } catch (_: Exception) {}
    }

    fun close(ctx: Context) {
        val v = view ?: return
        try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
        view = null
    }
}
