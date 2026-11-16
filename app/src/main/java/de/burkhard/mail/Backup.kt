package de.burkhard.mail

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sicherungsdatei: Konten (mit Passwörtern), KI-Schlüssel, Adressbuch, Gruppen, Regeln, Einstellungen.
 * Verschlüsselt mit AES-256-GCM, Schlüssel aus dem Sicherungs-Passwort (PBKDF2-SHA256).
 */
object Backup {
    private const val ITER = 120_000

    fun create(store: Store, pass: String, local: JSONObject?): String {
        if (pass.length < 6) throw UserError("Das Sicherungs-Passwort braucht mindestens 6 Zeichen.")
        val cfg = store.load()
        val pw = JSONObject()
        store.accounts().forEach { a -> val id = a.getString("id"); pw.put(id, store.password(id)) }
        val plain = JSONObject()
            .put("config", cfg).put("passwords", pw).put("aiKey", store.aiKey())
            .put("local", local ?: JSONObject()).put("created", System.currentTimeMillis())
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pass, salt, ITER), GCMParameterSpec(128, iv))
        val enc = c.doFinal(plain.toString().toByteArray(Charsets.UTF_8))
        return JSONObject().put("app", "Mail").put("format", 1).put("kdf", "PBKDF2WithHmacSHA256").put("iter", ITER)
            .put("salt", b64(salt)).put("iv", b64(iv)).put("data", b64(enc)).toString()
    }

    /** Liest die Datei, ersetzt Konten und Einstellungen. Gibt Anzahl Konten und die gespeicherten Ansicht-Einstellungen zurück. */
    fun restore(store: Store, fileText: String, pass: String): JSONObject {
        val f = try { JSONObject(fileText) } catch (_: Exception) { throw UserError("Das ist keine Sicherungsdatei von Mail.") }
        if (f.optString("app") != "Mail" || !f.has("data")) throw UserError("Das ist keine Sicherungsdatei von Mail.")
        val plainText = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(pass, unb64(f.getString("salt")), f.optInt("iter", ITER)), GCMParameterSpec(128, unb64(f.getString("iv"))))
            String(c.doFinal(unb64(f.getString("data"))), Charsets.UTF_8)
        } catch (_: AEADBadTagException) { throw UserError("Das Sicherungs-Passwort ist falsch.") }
          catch (e: UserError) { throw e }
          catch (_: Exception) { throw UserError("Das Sicherungs-Passwort ist falsch oder die Datei ist beschädigt.") }
        val plain = JSONObject(plainText)
        val cfg = plain.getJSONObject("config")
        // alte Passwörter entfernen, neue setzen
        store.accounts().forEach { store.removePassword(it.getString("id")) }
        val pw = plain.optJSONObject("passwords") ?: JSONObject()
        pw.keys().forEach { id -> store.setPassword(id, pw.optString(id)) }
        plain.optString("aiKey").takeIf { it.isNotBlank() }?.let { store.setAiKey(it) }
        store.save(cfg)
        return JSONObject().put("ok", true).put("accounts", cfg.optJSONArray("accounts")?.length() ?: 0)
            .put("local", plain.optJSONObject("local") ?: JSONObject())
    }

    private fun key(pass: String, salt: ByteArray, iter: Int): SecretKeySpec {
        val spec = PBEKeySpec(pass.toCharArray(), salt, iter, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
