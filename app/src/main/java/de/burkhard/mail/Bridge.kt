package de.burkhard.mail

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Verbindung zwischen Oberfläche (JavaScript) und App (Kotlin).
 * JavaScript ruft MailBridge.call(id, json) auf, die Antwort kommt über window.__reply(id, ok, json).
 */
class Bridge(private val act: MainActivity) {
    private val app = MailApp.app
    private val store = app.store
    private val core = app.core
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JavascriptInterface
    fun call(id: String, json: String) {
        scope.launch {
            val res = try {
                val b = JSONObject(json)
                true to handle(b)
            } catch (e: Throwable) {
                val msg = (e as? UserError)?.message ?: core.friendly(e, null).message ?: "Fehler"
                false to JSONObject().put("error", msg)
            }
            act.reply(id, res.first, res.second)
        }
    }

    @JavascriptInterface fun unlock() { act.runOnUiThread { act.askBiometric() } }
    @JavascriptInterface fun copy(text: String) {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Mail", text))
    }
    @JavascriptInterface fun openUrl(url: String) {
        try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
    @JavascriptInterface fun isApp(): Boolean = true

    private fun ok() = JSONObject().put("ok", true)

    private fun handle(b: JSONObject): JSONObject {
        when (b.optString("action")) {
            "login", "logout" -> return ok()
            "config" -> {
                val c = store.load()
                val accs = JSONArray()
                c.getJSONArray("accounts").let { a -> for (i in 0 until a.length()) accs.put(publicAcc(a.getJSONObject(i))) }
                return JSONObject().put("accounts", accs).put("rules", c.getJSONObject("rules")).put("settings", c.getJSONObject("settings"))
                    .put("contacts", c.getJSONArray("contacts")).put("groups", c.getJSONArray("groups"))
                    .put("phone", if (c.getJSONObject("settings").optBoolean("phoneSuggest", true)) phoneContacts(false) else JSONArray())
                    .put("hasAiKey", store.aiKey().isNotBlank())
            }
            "saveAccount" -> return saveAccount(b.getJSONObject("account"))
            "deleteAccount" -> {
                val c = store.load(); val id = b.getString("id")
                c.put("accounts", JSONArray(c.getJSONArray("accounts").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("id") != id } }))
                store.save(c); store.removePassword(id); core.drop(id); MailService.reload(act)
                return ok()
            }
            "saveRules" -> {
                val c = store.load()
                val r = b.getJSONObject("rules")
                fun clean(a: JSONArray?) = (a?.strings() ?: emptyList()).map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().toJson()
                c.put("rules", JSONObject().put("block", clean(r.optJSONArray("block"))).put("allow", clean(r.optJSONArray("allow"))))
                b.optJSONObject("settings")?.let { s -> val st = c.getJSONObject("settings"); s.keys().forEach { k -> st.put(k, s.get(k)) } }
                store.save(c)
                return ok().put("rules", c.getJSONObject("rules")).put("settings", c.getJSONObject("settings"))
            }
            "saveSettings" -> {
                val c = store.load(); val st = c.getJSONObject("settings")
                val p = b.getJSONObject("patch"); p.keys().forEach { k -> st.put(k, p.get(k)) }
                store.save(c)
                if (p.has("notify") || p.has("away")) MailService.reload(act)
                if (p.has("screenshots")) act.runOnUiThread { act.applySecure() }
                return ok().put("settings", st)
            }
            "saveInboxFolders" -> {
                val c = store.load(); val st = c.getJSONObject("settings")
                val m = st.optJSONObject("inboxFolders") ?: JSONObject()
                m.put(b.getString("acc"), b.getJSONArray("paths")); st.put("inboxFolders", m); store.save(c)
                return ok().put("settings", st)
            }
            "saveAiKey" -> { store.setAiKey(b.optString("key")); return ok().put("hasAiKey", store.aiKey().isNotBlank()) }
            "saveContact" -> {
                val c = store.load(); val arr = c.getJSONArray("contacts"); val ct = b.getJSONObject("contact")
                val email = ct.optString("email").trim()
                for (i in 0 until arr.length()) { val x = arr.getJSONObject(i); if (x.optString("email").equals(email, true) && x.optString("id") != ct.optString("id")) throw UserError("Diese Adresse steht schon bei einem anderen Kontakt.") }
                var id = ct.optString("id")
                if (id.isNotBlank() && id != "null") {
                    for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") == id) { ct.keys().forEach { k -> arr.getJSONObject(i).put(k, ct.get(k)) } }
                } else { id = "k" + System.currentTimeMillis(); ct.put("id", id); arr.put(ct) }
                store.save(c); return ok().put("id", id).put("contacts", arr)
            }
            "deleteContact" -> { val c = store.load(); c.put("contacts", filterOut(c.getJSONArray("contacts"), b.getString("id"))); store.save(c); return ok().put("contacts", c.getJSONArray("contacts")) }
            "saveGroup" -> {
                val c = store.load(); val arr = c.getJSONArray("groups"); val g = b.getJSONObject("group")
                var id = g.optString("id")
                if (id.isNotBlank() && id != "null") { for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") == id) { g.keys().forEach { k -> arr.getJSONObject(i).put(k, g.get(k)) } } }
                else { id = "g" + System.currentTimeMillis(); g.put("id", id); arr.put(g) }
                store.save(c); return ok().put("id", id).put("groups", arr)
            }
            "deleteGroup" -> { val c = store.load(); c.put("groups", filterOut(c.getJSONArray("groups"), b.getString("id"))); store.save(c); return ok().put("groups", c.getJSONArray("groups")) }
            "phoneContacts" -> return JSONObject().put("list", phoneContacts(true))
            "importContacts" -> {
                val c = store.load(); val arr = c.getJSONArray("contacts"); val have = arr.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("email").lowercase() }.toMutableSet() }
                val l = b.getJSONArray("list")
                for (i in 0 until l.length()) { val x = l.getJSONObject(i); val e = x.optString("email"); if (have.add(e.lowercase())) arr.put(JSONObject().put("id", "k" + System.nanoTime()).put("name", x.optString("name")).put("email", e).put("phone", "").put("org", "").put("note", "").put("phoneSrc", true)) }
                store.save(c); return ok().put("contacts", arr)
            }
            "overview" -> return core.overview(store.account(b.getString("acc")))
            "list" -> return core.list(store.account(b.getString("acc")), b.optString("path").takeIf { it.isNotBlank() && it != "null" },
                b.optString("role").takeIf { it.isNotBlank() && it != "null" }, b.optString("q"), minOf(200, b.optInt("limit", 50)), b.optBoolean("preview"))
            "read" -> return core.read(store.account(b.getString("acc")), b.getString("path"), b.getLong("uid"))
            "bulk" -> return core.bulk(b.getString("op"), b.getJSONArray("items"), b.optJSONObject("dest"), b.optBoolean("learn"))
            "empty" -> return ok().put("deleted", core.empty(store.account(b.getString("acc")), b.getString("path")))
            "createFolder" -> { core.createFolder(store.account(b.getString("acc")), b.optString("parent").takeIf { it.isNotBlank() && it != "null" }, b.getString("name").trim()); return ok() }
            "renameFolder" -> { core.renameFolder(store.account(b.getString("acc")), b.getString("path"), b.getString("name").trim()); return ok() }
            "deleteFolder" -> { core.deleteFolder(store.account(b.getString("acc")), b.getString("path")); return ok() }
            "send" -> { core.send(b, false); return ok().put("saved", true) }
            "draft" -> { core.send(b, true); return ok().put("saved", true) }
            "queue" -> return SendWorker.queue(act, b.getJSONObject("payload"), b.optLong("delay", 0))
            "recall" -> return JSONObject().put("payload", SendWorker.recall(act, b.getString("id")))
            "sendNow" -> { SendWorker.sendNow(act, b.getString("id")); return ok() }
            "outbox" -> return JSONObject().put("items", SendWorker.list())
            "attachment" -> { openAttachment(b); return ok() }
            "ai" -> return JSONObject().put("text", AiClient.ask(store.aiKey(), b.getString("prompt"), b.optString("tier", "default")))
            "inject" -> {
                val accs = store.accounts()
                val n = JSONObject().put("acc", accs.firstOrNull()?.optString("id") ?: "").put("path", "INBOX").put("uid", 0)
                    .put("from", "Mail").put("fromAddr", "").put("subject", "Test-Benachrichtigung")
                    .put("text", "So sieht eine neue Mail aus. Dein Bestätigungscode lautet 482913.")
                MailService.showNotification(act, n, forceSystem = true)
                return n
            }
            "ensureOverlay" -> {
                if (!Settings.canDrawOverlays(act)) act.runOnUiThread {
                    try { act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName))) } catch (_: Exception) {}
                }
                return ok().put("granted", Settings.canDrawOverlays(act))
            }
            "cacheSize" -> {
                val bytes = act.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                return JSONObject().put("mb", Math.round(bytes / 104857.6) / 10.0)
            }
            "clearCache" -> {
                act.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                act.runOnUiThread { act.web.clearCache(true) }
                return ok()
            }
            "openBattery" -> {
                act.runOnUiThread { try { act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.packageName))) } catch (_: Exception) {} }
                return ok()
            }
            else -> throw UserError("Unbekannte Aktion.")
        }
    }

    private fun filterOut(a: JSONArray, id: String) = JSONArray((0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("id") != id })

    private fun publicAcc(a: JSONObject) = JSONObject().apply {
        listOf("id", "name", "email", "color", "user", "imapHost", "imapPort", "smtpHost", "smtpPort").forEach { k -> if (a.has(k)) put(k, a.get(k)) }
    }

    private fun saveAccount(i: JSONObject): JSONObject {
        val email = i.optString("email").trim()
        if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email)) throw UserError("Bitte eine gültige E-Mail-Adresse eintragen.")
        if (i.optString("imapHost").isBlank() || i.optString("smtpHost").isBlank()) throw UserError("Bitte Posteingangs- und Postausgangsserver eintragen.")
        val c = store.load()
        val arr = c.getJSONArray("accounts")
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }
        val oldId = i.optString("id").takeIf { it.isNotBlank() && it != "null" }
        val old = all.firstOrNull { it.optString("id") == oldId }
        val pass = i.optString("pass")
        if (old == null && pass.isBlank()) throw UserError("Bitte das (App-)Passwort eintragen.")
        if (all.any { it.optString("email").equals(email, true) && it.optString("id") != oldId }) throw UserError("Diese Adresse ist schon als Konto vorhanden.")
        val id = old?.optString("id") ?: ("a" + System.currentTimeMillis())
        val next = JSONObject().put("id", id)
            .put("name", i.optString("name").trim().ifBlank { email.substringBefore('@') })
            .put("email", email).put("color", i.optString("color").ifBlank { "#0d8584" })
            .put("user", i.optString("user").trim().ifBlank { email })
            .put("imapHost", i.optString("imapHost").trim()).put("imapPort", i.optInt("imapPort", 993))
            .put("smtpHost", i.optString("smtpHost").trim()).put("smtpPort", i.optInt("smtpPort", 465))
        val usePass = pass.ifBlank { store.password(id) }
        val changed = old == null || pass.isNotBlank() || listOf("imapHost", "imapPort", "smtpHost", "smtpPort", "user").any { old.opt(it)?.toString() != next.opt(it)?.toString() }
        if (changed) {
            core.connectNew(next, usePass).close()
            core.testSmtp(next, usePass)
        }
        if (pass.isNotBlank()) store.setPassword(id, pass)
        val list = all.filter { it.optString("id") != id }.toMutableList()
        val idx = all.indexOfFirst { it.optString("id") == id }
        if (idx >= 0) list.add(idx, next) else list.add(next)
        c.put("accounts", JSONArray(list))
        val st = c.getJSONObject("settings")
        if (st.optString("defaultFrom").isBlank()) st.put("defaultFrom", id)
        val sd = st.optJSONObject("sigDefault") ?: JSONObject()
        if (!sd.has(id)) { sd.put(id, "s1"); st.put("sigDefault", sd) }
        val away = st.optJSONObject("away")
        if (away != null && old == null) { val a = away.optJSONArray("accounts") ?: JSONArray(); a.put(id); away.put("accounts", a) }
        store.save(c)
        core.drop(id)
        MailService.reload(act)
        return ok().put("account", publicAcc(next))
    }

    private fun phoneContacts(ask: Boolean): JSONArray {
        val out = JSONArray()
        if (ContextCompat.checkSelfPermission(act, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            if (!ask) return out
            if (!act.requestPermissionBlocking(Manifest.permission.READ_CONTACTS)) throw UserError("Ohne Erlaubnis kann Mail deine Kontakte nicht lesen. Du kannst sie in den Android-Einstellungen unter Apps → Mail → Berechtigungen erteilen.")
        }
        val seen = mutableSetOf<String>()
        act.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS),
            null, null, ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY + " ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0) ?: ""
                val mail = cur.getString(1) ?: continue
                if (seen.add(mail.lowercase())) out.put(JSONObject().put("name", name).put("email", mail))
            }
        }
        return out
    }

    private fun openAttachment(b: JSONObject) {
        val (name, type, bytes) = core.attachment(store.account(b.getString("acc")), b.getString("path"), b.getLong("uid"), b.getInt("i"))
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val mime = type.ifBlank { MimeTypeMap.getSingleton().getMimeTypeFromExtension(safe.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream" }
        if (b.optString("mode") == "save") {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe); put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw UserError("Speichern nicht möglich.")
                act.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                return
            }
        }
        val dir = File(act.cacheDir, "att").apply { mkdirs() }
        val f = File(dir, safe); f.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(act, act.packageName + ".files", f)
        val view = Intent(if (b.optString("mode") == "save") Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
            if (action == Intent.ACTION_SEND) { type = mime; putExtra(Intent.EXTRA_STREAM, uri) } else setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        act.runOnUiThread {
            try { act.startActivity(Intent.createChooser(view, "Öffnen mit")) }
            catch (_: Exception) { act.toastJs("Keine App zum Öffnen dieser Datei gefunden.") }
        }
    }
}
