package de.burkhard.mail

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Speicher der App: Konten, Regeln, Einstellungen, Adressbuch, Gruppen (normale Einstellungen)
 * und Passwörter / KI-Schlüssel (verschlüsselt über den Android-Schlüsselspeicher).
 */
class Store(ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("mail_cfg", Context.MODE_PRIVATE)
    private val secret: SharedPreferences = run {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "mail_secret", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Synchronized
    fun load(): JSONObject {
        val raw = prefs.getString("config", null)
        val c = if (raw != null) JSONObject(raw) else JSONObject()
        if (!c.has("accounts")) c.put("accounts", JSONArray())
        if (!c.has("rules")) c.put("rules", JSONObject().put("block", JSONArray()).put("allow", JSONArray()))
        if (!c.has("contacts")) c.put("contacts", JSONArray())
        if (!c.has("groups")) c.put("groups", JSONArray())
        val s = c.optJSONObject("settings") ?: JSONObject()
        defaults().keys().forEach { k -> if (!s.has(k)) s.put(k, defaults().get(k)) }
        c.put("settings", s)
        return c
    }

    @Synchronized
    fun save(c: JSONObject) {
        prefs.edit().putString("config", c.toString()).apply()
    }

    private fun defaults(): JSONObject = JSONObject("""
        {"autoSpam":false,"spamThreshold":4,"protect":"standard","undoDelay":300,"defaultFrom":"",
         "sigs":[{"id":"s1","name":"Standard","html":"Viele Grüße"}],"sigDefault":{},
         "away":{"on":false,"from":"","to":"","subject":"Abwesenheitsnotiz","text":"Vielen Dank für Ihre Nachricht.<br>Ich bin bis zum … nicht erreichbar und antworte danach so schnell wie möglich.","accounts":[],"once":true,"skipLists":true},
         "notify":{"instant":true,"popup":true,"code":true,"preview":true,"sound":true},
         "ai":true,"phoneSuggest":true,"lock":{"on":true,"after":1},"screenshots":true}
    """.trimIndent())

    fun password(accId: String): String = secret.getString("pw_$accId", "") ?: ""
    fun setPassword(accId: String, pw: String) { secret.edit().putString("pw_$accId", pw).apply() }
    fun removePassword(accId: String) { secret.edit().remove("pw_$accId").apply() }

    fun aiKey(): String = secret.getString("ai_key", "") ?: ""
    fun setAiKey(k: String) { secret.edit().putString("ai_key", k.trim()).apply() }

    // Kleine Ablage für Postausgang und Abwesenheits-Antworten
    fun getJson(name: String): JSONObject = JSONObject(prefs.getString(name, "{}") ?: "{}")
    fun putJson(name: String, o: JSONObject) { prefs.edit().putString(name, o.toString()).apply() }

    fun accounts(): List<JSONObject> {
        val a = load().getJSONArray("accounts")
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    fun account(id: String): JSONObject = accounts().firstOrNull { it.optString("id") == id }
        ?: throw UserError("Konto nicht gefunden.")
}

class UserError(msg: String) : Exception(msg)

fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
fun List<String>.toJson(): JSONArray = JSONArray().also { a -> forEach { a.put(it) } }
