package de.burkhard.mail

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Gemeinsame Objekte der App. */
class MailApp : Application() {
    lateinit var store: Store
    lateinit var core: MailCore

    override fun onCreate() {
        super.onCreate()
        app = this
        store = Store(this)
        core = MailCore(store)
        createChannels(this)
        // Sicherheitsnetz: alle 15 Minuten nach neuen Mails sehen, falls Android den Hintergrunddienst beendet hat
        try {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork("mailcheck", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        } catch (_: Exception) {}
    }

    companion object {
        lateinit var app: MailApp
        const val CH_MAIL = "neue_mails"
        const val CH_MAIL_QUIET = "neue_mails_fenster"
        const val CH_SERVICE = "verbindung"
        const val CH_INFO = "hinweise"

        fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_MAIL, "Neue E-Mails", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Meldung bei neuen E-Mails"
            })
            nm.createNotificationChannel(NotificationChannel(CH_MAIL_QUIET, "Neue E-Mails (mit Fenster)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Ton und Eintrag in der Leiste, wenn zusätzlich das Fenster über anderen Apps erscheint"
            })
            nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Verbindung zu den Konten", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Dauerhaftes Symbol, solange Mail auf neue E-Mails wartet"
                setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_INFO, "Hinweise", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}

/** Zustand der Hintergrund-Überwachung für die Seite „Benachrichtigungen → Status“. */
object Diag {
    @Volatile var serviceOn = false
    @Volatile var serviceSince = 0L
    private val lock = Any()
    fun service(on: Boolean) { serviceOn = on; if (on) serviceSince = System.currentTimeMillis() }
    private fun upd(id: String, f: (JSONObject) -> Unit) = synchronized(lock) {
        try {
            val st = MailApp.app.store
            val d = st.getJson("diag"); val o = d.optJSONObject(id) ?: JSONObject(); f(o); d.put(id, o); st.putJson("diag", d)
        } catch (_: Exception) {}
    }
    fun acc(id: String, state: String, err: String?) = upd(id) { o ->
        o.put("state", state).put("stateAt", System.currentTimeMillis())
        if (err != null) o.put("error", err).put("errorAt", System.currentTimeMillis())
    }
    fun checked(id: String, found: Int) = upd(id) { it.put("checkAt", System.currentTimeMillis()).put("found", found) }
    fun notified(id: String, subject: String) = upd(id) { it.put("notifyAt", System.currentTimeMillis()).put("notifySubject", subject.take(80)) }
}

/** Prüft alle 15 Minuten die Posteingänge und meldet Mails, die der Hintergrunddienst verpasst hat. */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = MailApp.app
        if (app.store.load().getJSONObject("settings").optJSONObject("notify")?.optBoolean("instant", true) == false) return Result.success()
        for (acc in app.store.accounts()) {
            try {
                val st = app.core.connectNew(acc, app.store.password(acc.getString("id")))
                try {
                    val inbox = st.getFolder("INBOX") as com.sun.mail.imap.IMAPFolder
                    inbox.open(javax.mail.Folder.READ_ONLY)
                    try { MailService.checkNew(applicationContext, acc, inbox) } finally { try { inbox.close(false) } catch (_: Exception) {} }
                } finally { try { st.close() } catch (_: Exception) {} }
            } catch (_: Exception) {}
        }
        try { MailService.start(applicationContext) } catch (_: Exception) {}
        return Result.success()
    }
}

/** Startet den Hintergrunddienst nach dem Einschalten des Handys oder nach einem Update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        MailService.start(ctx)
    }
}

/** Knöpfe in der Benachrichtigung: Code kopieren, Text kopieren, Schließen. */
class NotifyActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("nid", 0)
        when (intent.action) {
            ACTION_COPY -> {
                val text = intent.getStringExtra("text") ?: ""
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Mail", text))
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Kopiert: ${text.take(40)}", Toast.LENGTH_SHORT).show()
                if (intent.getBooleanExtra("close", false)) NotificationManagerCompat.from(ctx).cancel(id)
            }
            ACTION_CLOSE -> NotificationManagerCompat.from(ctx).cancel(id)
        }
    }

    companion object {
        const val ACTION_COPY = "de.burkhard.mail.COPY"
        const val ACTION_CLOSE = "de.burkhard.mail.CLOSE"
    }
}

/** Zeitversetztes Senden mit „Zurückholen“. Die Mail liegt bis zum Versand im Postausgang. */
class SendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val app = MailApp.app
        val box = app.store.getJson("outbox")
        val item = box.optJSONObject(id) ?: return Result.success()
        return try {
            app.core.send(item.getJSONObject("payload"), false)
            val b2 = app.store.getJson("outbox"); b2.remove(id); app.store.putJson("outbox", b2)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else {
                val b2 = app.store.getJson("outbox"); b2.remove(id); app.store.putJson("outbox", b2)
                info(applicationContext, "Senden fehlgeschlagen", "„${item.getJSONObject("payload").optString("subject")}“: ${e.message}")
                Result.failure()
            }
        }
    }

    companion object {
        fun queue(ctx: Context, payload: JSONObject, delaySec: Long): JSONObject {
            val id = "o" + System.currentTimeMillis()
            val sendAt = System.currentTimeMillis() + delaySec * 1000
            val box = MailApp.app.store.getJson("outbox")
            box.put(id, JSONObject().put("payload", payload).put("sendAt", sendAt))
            MailApp.app.store.putJson("outbox", box)
            val req = OneTimeWorkRequestBuilder<SendWorker>().setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setInputData(workDataOf("id" to id)).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, req)
            return JSONObject().put("id", id).put("sendAt", sendAt)
        }

        fun recall(ctx: Context, id: String): JSONObject {
            val box = MailApp.app.store.getJson("outbox")
            val item = box.optJSONObject(id) ?: throw UserError("Diese Mail ist schon unterwegs.")
            WorkManager.getInstance(ctx).cancelUniqueWork(id)
            box.remove(id); MailApp.app.store.putJson("outbox", box)
            return item.getJSONObject("payload")
        }

        fun sendNow(ctx: Context, id: String) {
            val box = MailApp.app.store.getJson("outbox")
            val item = box.optJSONObject(id) ?: throw UserError("Diese Mail ist schon unterwegs.")
            WorkManager.getInstance(ctx).cancelUniqueWork(id)
            MailApp.app.core.send(item.getJSONObject("payload"), false)
            box.remove(id); MailApp.app.store.putJson("outbox", box)
        }

        fun list(): JSONArray {
            val box = MailApp.app.store.getJson("outbox")
            val out = JSONArray()
            box.keys().forEach { k -> val it = box.getJSONObject(k); out.put(JSONObject().put("id", k).put("payload", it.getJSONObject("payload")).put("sendAt", it.getLong("sendAt"))) }
            return out
        }
    }
}

fun info(ctx: Context, title: String, text: String) {
    try {
        val n = NotificationCompat.Builder(ctx, MailApp.CH_INFO).setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt(), n)
    } catch (_: SecurityException) {}
}

/** KI über die Anthropic-Schnittstelle mit dem eigenen Schlüssel. Wird nur auf Knopfdruck aufgerufen. */
object AiClient {
    fun ask(key: String, prompt: String, tier: String): String {
        if (key.isBlank()) throw UserError("Bitte unter Einstellungen → KI-Assistent deinen KI-Schlüssel eintragen.")
        val model = if (tier == "quick") "claude-haiku-5-5" else "claude-sonnet-5-5"
        val body = JSONObject().put("model", model).put("max_tokens", 1500)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 20000; c.readTimeout = 120000; c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code == 401) throw UserError("Der KI-Schlüssel wurde abgelehnt. Bitte in den Einstellungen prüfen.")
            if (code == 429) throw UserError("Gerade zu viele KI-Anfragen. Bitte gleich nochmal versuchen.")
            if (code !in 200..299) throw UserError("Die KI hat nicht geantwortet (Fehler $code).")
            val arr = JSONObject(txt).getJSONArray("content")
            return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        } finally { c.disconnect() }
    }
}
