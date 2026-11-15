package de.burkhard.mail

import org.json.JSONObject

/** Eigene Spam-Bewertung: Sperr-/Erlaubt-Liste, SPF/DKIM/DMARC, gefälschte Absender, typische Betreffs. */
object Spam {
    data class Result(val score: Int, val reasons: List<String>, val allowed: Boolean = false, val blocked: Boolean = false)

    private val WORDS = Regex(
        "(gewinn|gewonnen|lotterie|lottery|winner|erbschaft|inheritance|bitcoin|krypto|crypto|casino|viagra|cialis|" +
            "konto (wurde )?gesperrt|account (has been )?(suspended|locked)|verifizieren sie|bestätigen sie ihr konto|" +
            "dringend|urgent|sofort handeln|100% gratis|free money|freispiele)", RegexOption.IGNORE_CASE
    )
    private val MAIL_IN_NAME = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.-]+")

    private fun dom(a: String) = a.lowercase().substringAfter('@', "")

    fun matchRule(list: List<String>, address: String): Boolean {
        val a = address.lowercase().trim()
        val d = dom(a)
        return list.any { r0 ->
            val r = r0.lowercase().trim()
            if (r.isEmpty()) false
            else if (r.contains('@') && !r.startsWith("@")) r == a
            else {
                val rd = r.removePrefix("@")
                d == rd || d.endsWith(".$rd")
            }
        }
    }

    fun score(fromName: String, fromAddr: String, replyTo: String?, subject: String, auth: String, rules: JSONObject): Result {
        val allow = rules.optJSONArray("allow")?.strings() ?: emptyList()
        val block = rules.optJSONArray("block")?.strings() ?: emptyList()
        if (matchRule(allow, fromAddr)) return Result(0, listOf("Absender auf deiner Erlaubt-Liste"), allowed = true)
        if (matchRule(block, fromAddr)) return Result(99, listOf("Absender auf deiner Sperrliste"), blocked = true)
        var s = 0
        val r = mutableListOf<String>()
        val a = auth.lowercase()
        if (Regex("\\bspf=fail").containsMatchIn(a)) { s += 2; r += "SPF-Prüfung fehlgeschlagen" }
        else if (Regex("\\bspf=softfail").containsMatchIn(a)) { s += 1; r += "SPF nur teilweise bestanden" }
        if (Regex("\\bdkim=fail").containsMatchIn(a)) { s += 2; r += "DKIM-Signatur ungültig" }
        if (Regex("\\bdmarc=fail").containsMatchIn(a)) { s += 3; r += "DMARC fehlgeschlagen" }
        MAIL_IN_NAME.find(fromName)?.let { if (dom(it.value) != dom(fromAddr)) { s += 3; r += "Anzeigename täuscht eine andere Adresse vor" } }
        if (!replyTo.isNullOrBlank() && dom(replyTo).isNotEmpty() && dom(replyTo) != dom(fromAddr)) { s += 1; r += "Antworten gehen an eine fremde Domain" }
        val letters = subject.filter { it.isLetter() }
        if (letters.length > 10 && letters.count { it.isUpperCase() }.toDouble() / letters.length > 0.7) { s += 1; r += "Betreff fast nur in Großbuchstaben" }
        if (subject.contains("!!") || subject.contains("$$") || subject.contains("€€")) { s += 1; r += "Auffällige Zeichen im Betreff" }
        if (WORDS.containsMatchIn(subject)) { s += 2; r += "Typische Spam-Begriffe im Betreff" }
        return Result(s, r)
    }
}
