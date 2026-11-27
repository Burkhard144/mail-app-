package de.burkhard.mail

import android.util.Base64
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPMessage
import com.sun.mail.imap.IMAPStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import javax.mail.Address
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility
import javax.mail.search.BodyTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.RecipientStringTerm
import javax.mail.search.SubjectTerm

/** IMAP/SMTP-Zugriff auf die Mailkonten. Alle Aktionen wirken direkt auf dem Server. */
class MailCore(private val store: Store) {

    data class FolderInfo(val path: String, val name: String, var role: String, val delimiter: Char, val parent: String?, val depth: Int)

    private val stores = ConcurrentHashMap<String, IMAPStore>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val folderCache = ConcurrentHashMap<String, Pair<Long, List<FolderInfo>>>()

    private fun lockOf(id: String) = locks.getOrPut(id) { Any() }

    // ---------- Verbindung ----------
    private fun imapProps(acc: JSONObject): Properties {
        val port = acc.optInt("imapPort", 993)
        val ssl = port == 993
        val p = Properties()
        val proto = if (ssl) "imaps" else "imap"
        p["mail.store.protocol"] = proto
        p["mail.$proto.host"] = acc.optString("imapHost")
        p["mail.$proto.port"] = port.toString()
        p["mail.$proto.connectiontimeout"] = "15000"
        p["mail.$proto.timeout"] = "30000"
        p["mail.$proto.writetimeout"] = "30000"
        p["mail.$proto.partialfetch"] = "false"
        p["mail.$proto.fetchsize"] = "1048576"
        if (ssl) p["mail.$proto.ssl.enable"] = "true" else p["mail.$proto.starttls.enable"] = "true"
        p["mail.$proto.ssl.checkserveridentity"] = "true"
        return p
    }

    /** idle = true: Verbindung für die Hintergrund-Überwachung – darf lange still sein (IMAP IDLE). */
    fun connectNew(acc: JSONObject, pass: String, idle: Boolean = false): IMAPStore {
        val p = imapProps(acc)
        val proto = p.getProperty("mail.store.protocol")
        if (idle) p["mail.$proto.timeout"] = (10 * 60_000).toString()
        val s = Session.getInstance(p).getStore(proto) as IMAPStore
        val t0 = android.os.SystemClock.elapsedRealtime()
        val who = acc.optString("name").ifBlank { acc.optString("email") } + if (idle) " (Wächter)" else ""
        try {
            s.connect(acc.optString("imapHost"), acc.optInt("imapPort", 993), user(acc), pass)
        } catch (e: Exception) { Diag.timing("anmelden", who, android.os.SystemClock.elapsedRealtime() - t0, false); throw friendly(e, acc) }
        Diag.timing("anmelden", who, android.os.SystemClock.elapsedRealtime() - t0)
        return s
    }

    private fun user(acc: JSONObject) = acc.optString("user").ifBlank { acc.optString("email") }

    /** Je Konto zwei Verbindungen: "" für Listen/Aktionen, READ fürs Öffnen von Mails – so wartet das Öffnen nie auf das Laden der Liste. */
    private val READ = "#lesen"

    fun storeFor(acc: JSONObject, slot: String = ""): IMAPStore {
        val id = acc.getString("id")
        val key = id + slot
        val cur = stores[key]
        if (cur != null && cur.isConnected) return cur
        try { cur?.close() } catch (_: Exception) {}
        val s = connectNew(acc, store.password(id))
        stores[key] = s
        return s
    }

    private fun dropKey(key: String) {
        openRead.remove(key)?.let { try { it.close(false) } catch (_: Exception) {} }
        try { stores.remove(key)?.close() } catch (_: Exception) {}
    }

    /** Vorschautexte nachladen, nachdem die Liste schon angezeigt wird. */
    fun previews(acc: JSONObject, path: String, uids: List<Long>): JSONObject {
        val out = JSONObject()
        if (uids.isEmpty()) return JSONObject().put("map", out)
        withAcc(acc) { s ->
            val f = s.getFolder(path) as IMAPFolder
            f.open(Folder.READ_ONLY)
            try {
                val msgs = f.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
                val fp = FetchProfile(); fp.add(FetchProfile.Item.CONTENT_INFO); fp.add(UIDFolder.FetchProfileItem.UID)
                f.fetch(msgs, fp)
                for (m in msgs) try { (m as IMAPMessage).peek = true; out.put(f.getUID(m).toString(), snippet(m)) } catch (_: Exception) {}
            } finally { try { f.close(false) } catch (_: Exception) {} }
        }
        return JSONObject().put("map", out)
    }
    fun drop(id: String) { dropKey(id); dropKey(id + READ); dropKey(id + "#zaehler"); folderCache.remove(id); synchronized(readCache) { readCache.keys.removeAll { it.startsWith("$id|") } } }
    fun dropAll() { stores.keys.toList().forEach { dropKey(it) }; folderCache.clear(); synchronized(readCache) { readCache.clear() } }

    /**
     * Nach einer Pause oder einem Netzwechsel (WLAN ↔ Mobilfunk) sind die offenen Verbindungen oft „tot“,
     * ohne dass das Handy es merkt – die nächste Anfrage würde bis zur Zeitgrenze (30 s) hängen.
     * Deshalb alle Verbindungen sofort verwerfen; die nächste Anfrage baut in ca. 1 s eine frische auf.
     */
    fun resetConnections() {
        val old = stores.values.toList() + openRead.values.toList()
        stores.clear(); openRead.clear()
        if (old.isNotEmpty()) Thread { old.forEach { try { if (it is IMAPFolder) it.close(false) else (it as IMAPStore).close() } catch (_: Exception) {} } }.start()
    }

    private fun <T> withAcc(acc: JSONObject, slot: String = "", block: (IMAPStore) -> T): T {
        val key = acc.getString("id") + slot
        synchronized(lockOf(key)) {
            return try { block(storeFor(acc, slot)) } catch (e: UserError) { throw e } catch (e: MessagingException) {
                // einmal neu verbinden und erneut versuchen
                dropKey(key)
                try { block(storeFor(acc, slot)) } catch (e2: UserError) { throw e2 } catch (e2: Exception) { throw friendly(e2, acc) }
            }
        }
    }

    /** Zuletzt geöffnete Mails im Speicher – erneutes Öffnen geht sofort. */
    private val readCache = object : LinkedHashMap<String, JSONObject>(40, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, JSONObject>?) = size > 40
    }

    fun friendly(e: Throwable, acc: JSONObject?): UserError {
        val name = acc?.let { (it.optString("name").ifBlank { it.optString("email") }) + ": " } ?: ""
        val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return when {
            e is UserError -> e
            Regex("simultaneous|too many|bandwidth|throttl|overquota|limit", RegexOption.IGNORE_CASE).containsMatchIn(m) ->
                UserError(name + "Der Server lässt gerade keine weitere Verbindung zu. Bitte kurz warten und nochmal versuchen. (Server: ${m.trim().take(120)})")
            e is javax.mail.AuthenticationFailedException || Regex("AUTHENTICATIONFAILED|authentication failed|invalid credentials|login failed|web login|application-specific|username and password", RegexOption.IGNORE_CASE).containsMatchIn(m) ->
                UserError(name + "Anmeldung abgelehnt. Prüfe das App-Passwort und ob IMAP beim Anbieter freigeschaltet ist. (Server: ${m.trim().take(120)})")
            Regex("UnknownHost|ENOTFOUND|resolve", RegexOption.IGNORE_CASE).containsMatchIn(m) -> UserError(name + "Server nicht gefunden. Prüfe die Serveradresse oder deine Internetverbindung.")
            Regex("timed? ?out|timeout", RegexOption.IGNORE_CASE).containsMatchIn(m) -> UserError(name + "Der Mailserver antwortet nicht rechtzeitig. Bitte gleich nochmal versuchen.")
            else -> UserError(name + (e.message ?: "Unbekannter Fehler"))
        }
    }

    /** Ordnernamen mit Umlauten liegen auf dem Server als „H&APY-he“ (modifiziertes UTF-7). Für die Anzeige zurückwandeln. */
    private fun decodeMutf7(s: String): String = try {
        Regex("&([A-Za-z0-9+,]*)-").replace(s) { m ->
            val b = m.groupValues[1]
            if (b.isEmpty()) "&" else {
                val pad = b.replace(',', '/') + "=".repeat((4 - b.length % 4) % 4)
                String(android.util.Base64.decode(pad, android.util.Base64.DEFAULT), Charsets.UTF_16BE)
            }
        }
    } catch (_: Exception) { s }

    // ---------- Ordner ----------
    private val NAME_RULES = listOf(
        "junk" to Regex("^(spam|junk|spamverdacht|unerw[uü]nscht|bulk)", RegexOption.IGNORE_CASE),
        "trash" to Regex("^(trash|papierkorb|gel[oö]scht|deleted|bin)\\b", RegexOption.IGNORE_CASE),
        "sent" to Regex("^(sent|gesendet)", RegexOption.IGNORE_CASE),
        "drafts" to Regex("^(drafts?|entw[uü]rfe?)", RegexOption.IGNORE_CASE),
        "archive" to Regex("^(archiv)", RegexOption.IGNORE_CASE)
    )
    private val SPECIAL = mapOf("\\Sent" to "sent", "\\Drafts" to "drafts", "\\Trash" to "trash", "\\Junk" to "junk",
        "\\Archive" to "archive", "\\All" to "all", "\\Flagged" to "flagged", "\\Important" to "important")

    fun folders(acc: JSONObject, fresh: Boolean = false, slot: String = ""): List<FolderInfo> {
        val id = acc.getString("id")
        val c = folderCache[id]
        val age = if (c != null) System.currentTimeMillis() - c.first else Long.MAX_VALUE
        if (age < (if (fresh) 60_000 else 5 * 60_000)) return c!!.second
        val list = withAcc(acc, slot) { s ->
            val out = mutableListOf<FolderInfo>()
            for (f in s.defaultFolder.list("*")) {
                val imf = f as IMAPFolder
                val attrs = try { imf.attributes.toList() } catch (_: Exception) { emptyList() }
                if (attrs.any { it.equals("\\Noselect", true) || it.equals("\\NonExistent", true) }) continue
                if ((f.type and Folder.HOLDS_MESSAGES) == 0) continue
                var role: String? = if (f.fullName.equals("INBOX", true)) "inbox" else attrs.firstNotNullOfOrNull { SPECIAL[it] }
                if (role == null) role = NAME_RULES.firstOrNull { it.second.containsMatchIn(f.name) }?.first
                if (role == "all" || role == "flagged" || role == "important") continue
                val sep = try { f.separator } catch (_: Exception) { '/' }
                val parts = f.fullName.split(sep)
                out += FolderInfo(f.fullName, if (role == "inbox") "Posteingang" else decodeMutf7(f.name), role ?: "custom", sep,
                    if (parts.size > 1) parts.dropLast(1).joinToString(sep.toString()) else null, maxOf(0, parts.size - 1))
            }
            val seen = mutableSetOf<String>()
            for (f in out) if (f.role != "custom" && f.role != "archive") { if (!seen.add(f.role)) f.role = "custom" }
            out
        }
        folderCache[id] = System.currentTimeMillis() to list
        return list
    }

    fun byRole(fs: List<FolderInfo>, role: String) = fs.firstOrNull { it.role == role }?.path
    private fun roleOf(fs: List<FolderInfo>, path: String) = fs.firstOrNull { it.path == path }?.role ?: "custom"

    /** Eigene Verbindung für Ordner und Zähler – blockiert so weder Liste noch Löschen/Verschieben. */
    private val INFO = "#zaehler"

    fun overview(acc: JSONObject): JSONObject = try { overviewOn(acc, INFO) } catch (e: UserError) {
        dropKey(acc.getString("id") + INFO)
        overviewOn(acc, "")
    }

    private fun overviewOn(acc: JSONObject, slot: String): JSONObject {
        val fs = folders(acc, true, slot)
        val counts = JSONObject()
        val unseen = JSONObject()
        withAcc(acc, slot) { s ->
            for (f in fs.take(40)) {
                try {
                    val fo = s.getFolder(f.path)
                    val u = fo.unreadMessageCount
                    if (u > 0) unseen.put(f.path, u)
                    if (f.role in listOf("inbox", "junk", "drafts")) counts.put(f.role, JSONObject().put("unseen", u).put("messages", fo.messageCount))
                } catch (_: Exception) {}
            }
        }
        return JSONObject().put("folders", foldersJson(fs)).put("counts", counts).put("unseen", unseen)
    }

    private fun foldersJson(fs: List<FolderInfo>) = JSONArray().also { a ->
        fs.forEach { f -> a.put(JSONObject().put("path", f.path).put("name", f.name).put("role", f.role)
            .put("delimiter", f.delimiter.toString()).put("parent", f.parent ?: JSONObject.NULL).put("depth", f.depth)) }
    }

    fun createFolder(acc: JSONObject, parent: String?, name: String) {
        if (name.isBlank()) throw UserError("Bitte einen Ordnernamen eingeben.")
        withAcc(acc) { s ->
            val sep = if (parent != null) s.getFolder(parent).separator else s.defaultFolder.separator
            val f = s.getFolder(if (parent != null) "$parent$sep$name" else name)
            if (f.exists()) throw UserError("Diesen Ordner gibt es schon.")
            if (!f.create(Folder.HOLDS_MESSAGES)) throw UserError("Der Server hat den Ordner nicht angelegt.")
        }
        folderCache.remove(acc.getString("id"))
    }

    fun renameFolder(acc: JSONObject, path: String, name: String) {
        val f = folders(acc).firstOrNull { it.path == path }
        if (f == null || f.role != "custom") throw UserError("Systemordner können nicht umbenannt werden.")
        withAcc(acc) { s ->
            val nn = if (f.parent != null) f.parent + f.delimiter + name else name
            val fo = s.getFolder(path)
            if (!fo.renameTo(s.getFolder(nn))) throw UserError("Der Server hat den Ordner nicht umbenannt.")
        }
        folderCache.remove(acc.getString("id"))
    }

    fun deleteFolder(acc: JSONObject, path: String) {
        val f = folders(acc).firstOrNull { it.path == path }
        if (f == null || f.role != "custom") throw UserError("Systemordner können nicht gelöscht werden.")
        withAcc(acc) { s -> s.getFolder(path).delete(true) }
        folderCache.remove(acc.getString("id"))
    }

    // ---------- Liste ----------
    private fun addr(a: Array<Address>?): InternetAddress? = a?.firstOrNull() as? InternetAddress

    fun list(acc: JSONObject, pathIn: String?, roleIn: String?, q: String, limit: Int, wantPreview: Boolean): JSONObject {
        val cfg = store.load()
        val rules = cfg.getJSONObject("rules")
        val settings = cfg.getJSONObject("settings")
        // Posteingang direkt öffnen – ohne vorher die komplette Ordnerliste zu holen (bei Gmail mit vielen Labels dauert das Sekunden).
        // Die Ordnerliste wird nur gebraucht, wenn sie schon im Speicher liegt oder ein anderer Ordner gewünscht ist.
        val inboxFast = pathIn == null && (roleIn ?: "inbox") == "inbox" || pathIn.equals("INBOX", true)
        val fsLazy = lazy { folders(acc) }
        val path = if (inboxFast) "INBOX" else pathIn ?: byRole(fsLazy.value, roleIn ?: "inbox") ?: return JSONObject().put("items", JSONArray()).put("total", 0).put("moved", 0).put("path", JSONObject.NULL)
        val role = if (inboxFast) "inbox" else roleOf(fsLazy.value, path)
        val items = mutableListOf<JSONObject>()
        val toJunk = mutableListOf<Long>()
        var total = 0
        withAcc(acc) { s ->
            val f = s.getFolder(path) as IMAPFolder
            f.open(Folder.READ_ONLY)
            try {
                total = f.messageCount
                if (total > 0) {
                    val msgs: Array<Message> = if (q.isNotBlank()) {
                        val term = OrTerm(arrayOf(FromStringTerm(q), SubjectTerm(q), RecipientStringTerm(Message.RecipientType.TO, q), BodyTerm(q)))
                        f.search(term).takeLast(limit).toTypedArray()
                    } else f.getMessages(maxOf(1, total - limit + 1), total)
                    val fp = FetchProfile()
                    fp.add(FetchProfile.Item.ENVELOPE); fp.add(FetchProfile.Item.FLAGS); fp.add(FetchProfile.Item.CONTENT_INFO)
                    fp.add(UIDFolder.FetchProfileItem.UID)
                    fp.add("Authentication-Results"); fp.add("List-Unsubscribe"); fp.add("Reply-To")
                    f.fetch(msgs, fp)
                    val isInbox = path.equals("INBOX", true)
                    val previewFrom = msgs.size - 30
                    msgs.forEachIndexed { idx, m ->
                        try {
                            val uid = f.getUID(m)
                            val fr = addr(m.from)
                            val fromAddr = fr?.address ?: ""
                            val fromName = fr?.personal ?: fromAddr
                            val subject = m.subject ?: ""
                            val auth = (m.getHeader("Authentication-Results") ?: emptyArray()).joinToString(" ")
                            val rt = addr(m.replyTo)?.address
                            val sc = Spam.score(fromName, fromAddr, rt, subject, auth, rules)
                            val autoSpam = settings.optBoolean("autoSpam") && !sc.allowed && sc.score >= settings.optInt("spamThreshold", 4)
                            if (isInbox && (sc.blocked || autoSpam)) { toJunk += uid; return@forEachIndexed }
                            val o = JSONObject()
                                .put("uid", uid).put("from", fromName.ifBlank { "(unbekannt)" }).put("fromAddr", fromAddr)
                                .put("to", (m.getRecipients(Message.RecipientType.TO) ?: emptyArray()).mapNotNull { (it as? InternetAddress)?.address }.joinToString(", "))
                                .put("subject", subject)
                                .put("date", (m.receivedDate ?: m.sentDate ?: Date()).time)
                                .put("seen", m.flags.contains(Flags.Flag.SEEN))
                                .put("flagged", m.flags.contains(Flags.Flag.FLAGGED))
                                .put("score", sc.score).put("reasons", sc.reasons.toJson()).put("allowed", sc.allowed)
                                .put("unsub", m.getHeader("List-Unsubscribe") != null)
                                .put("hasAtt", (m.contentType ?: "").lowercase().startsWith("multipart/mixed"))
                            if (wantPreview && idx >= previewFrom) {
                                try { (m as IMAPMessage).peek = true; o.put("preview", snippet(m)) } catch (_: Exception) {}
                            }
                            items += o
                        } catch (_: Exception) {}
                    }
                }
            } finally { try { f.close(false) } catch (_: Exception) {} }
        }
        var moved = 0
        val junk = if (toJunk.isNotEmpty()) byRole(fsLazy.value, "junk") else null
        if (toJunk.isNotEmpty() && junk != null && junk != path) {
            try { moveUids(acc, path, toJunk, junk); moved = toJunk.size } catch (_: Exception) {}
        }
        items.sortByDescending { it.optLong("date") }
        return JSONObject().put("items", JSONArray(items)).put("total", total).put("moved", moved).put("path", path).put("role", role)
    }

    private fun snippet(m: Message): String {
        val t = textOf(m, 0) ?: return ""
        return t.replace(Regex("\\s+"), " ").trim().take(180)
    }

    private fun textOf(p: Part, depth: Int): String? {
        if (depth > 6) return null
        return when {
            p.isMimeType("text/plain") -> p.content as? String
            p.isMimeType("text/html") -> (p.content as? String)?.let { htmlToText(it) }
            p.isMimeType("multipart/*") -> {
                val mp = p.content as Multipart
                var html: String? = null
                for (i in 0 until mp.count) {
                    val bp = mp.getBodyPart(i)
                    if (Part.ATTACHMENT.equals(bp.disposition, true)) continue
                    if (bp.isMimeType("text/plain")) return bp.content as? String
                    if (html == null) html = textOf(bp, depth + 1)
                }
                html
            }
            else -> null
        }
    }

    private fun htmlToText(h: String) = h.replace(Regex("(?is)<(style|script)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>"), "\n").replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()

    // ---------- Lesen ----------
    private class Parsed {
        var text: String? = null
        var html: String? = null
        val atts = mutableListOf<Part>()
        val inline = mutableMapOf<String, Part>()
    }

    private fun walk(p: Part, out: Parsed, depth: Int = 0) {
        if (depth > 8) return
        val disp = p.disposition
        val fname = try { p.fileName?.let { MimeUtility.decodeText(it) } } catch (_: Exception) { p.fileName }
        when {
            p.isMimeType("multipart/*") -> {
                val mp = p.content as Multipart
                for (i in 0 until mp.count) walk(mp.getBodyPart(i), out, depth + 1)
            }
            p.isMimeType("message/rfc822") && fname == null -> walk(p.content as Part, out, depth + 1)
            Part.ATTACHMENT.equals(disp, true) || (fname != null && !p.isMimeType("text/*")) -> {
                val cid = (p as? MimeBodyPart)?.contentID?.trim('<', '>')
                if (cid != null && p.isMimeType("image/*") && !Part.ATTACHMENT.equals(disp, true)) out.inline[cid] = p
                else out.atts += p
            }
            p.isMimeType("text/plain") && out.text == null -> out.text = p.content as? String
            p.isMimeType("text/html") && out.html == null -> out.html = p.content as? String
            p.isMimeType("image/*") -> {
                val cid = (p as? MimeBodyPart)?.contentID?.trim('<', '>')
                if (cid != null) out.inline[cid] = p else out.atts += p
            }
        }
    }

    /** Auf der Lese-Verbindung bleibt der zuletzt benutzte Ordner geöffnet – spart bei jeder Mail zwei Server-Rundläufe. */
    private val openRead = ConcurrentHashMap<String, IMAPFolder>()

    private fun readFolder(s: IMAPStore, key: String, path: String, keep: Boolean): IMAPFolder {
        if (!keep) return (s.getFolder(path) as IMAPFolder).also { it.open(Folder.READ_WRITE) }
        val cur = openRead[key]
        if (cur != null && cur.isOpen && cur.fullName == path && cur.store === s) return cur
        try { cur?.close(false) } catch (_: Exception) {}
        val f = s.getFolder(path) as IMAPFolder
        f.open(Folder.READ_WRITE)
        openRead[key] = f
        return f
    }

    fun read(acc: JSONObject, path: String, uid: Long, markSeen: Boolean = true, viaMain: Boolean = false): JSONObject {
        val ck = acc.getString("id") + "|" + path + "|" + uid
        synchronized(readCache) { readCache[ck] }?.let { cached ->
            if (!markSeen) return cached
            // sofort anzeigen, „gelesen“ im Hintergrund auf dem Server setzen
            Thread {
                try { withAcc(acc, READ) { s ->
                    readFolder(s, acc.getString("id") + READ, path, true).getMessageByUID(uid)?.setFlag(Flags.Flag.SEEN, true)
                } } catch (_: Exception) {}
            }.start()
            return cached
        }
        val rules = store.load().getJSONObject("rules")
        // Vorladen nutzt die schon offene Hauptverbindung – so bleibt die Lese-Verbindung frei und es gibt beim Start keine Extra-Anmeldungen
        if (viaMain) return readOn(acc, path, uid, "", ck, rules, markSeen)
        // Lese-Verbindung noch nicht angemeldet, Hauptverbindung schon → sofort darüber lesen und die Lese-Verbindung nebenbei aufbauen
        val id = acc.getString("id")
        if (stores[id + READ]?.isConnected != true && stores[id]?.isConnected == true) {
            Thread { try { withAcc(acc, READ) { } } catch (_: Exception) {} }.start()
            return readOn(acc, path, uid, "", ck, rules, markSeen)
        }
        return try { readOn(acc, path, uid, READ, ck, rules, markSeen) } catch (e: UserError) {
            if (e.message?.contains("nicht mehr gefunden") == true) throw e
            // zweite Verbindung abgelehnt (z. B. Gmail) → über die Hauptverbindung lesen
            openRead.remove(acc.getString("id") + READ)
            dropKey(acc.getString("id") + READ)
            readOn(acc, path, uid, "", ck, rules, markSeen)
        }
    }

    private fun readOn(acc: JSONObject, path: String, uid: Long, slot: String, ck: String, rules: JSONObject, markSeen: Boolean): JSONObject {
        val keep = slot == READ
        return withAcc(acc, slot) { s ->
            val f = readFolder(s, acc.getString("id") + slot, path, keep)
            try {
                val im = f.getMessageByUID(uid) ?: throw UserError("Die E-Mail wurde auf dem Server nicht mehr gefunden.")
                (im as IMAPMessage).peek = true
                // Mail bis 5 MB in EINEM Rutsch holen und auf dem Handy zerlegen – statt jedes Teil (Text, HTML, Bilder) einzeln vom Server
                val fpS = FetchProfile(); fpS.add(FetchProfile.Item.SIZE); fpS.add(FetchProfile.Item.ENVELOPE); f.fetch(arrayOf<Message>(im), fpS)
                val m: Message = if (im.size in 0..5_000_000) try { MimeMessage(im) } catch (_: Exception) { im } else im
                val p = Parsed()
                walk(m, p)
                if (markSeen) try { im.setFlag(Flags.Flag.SEEN, true) } catch (_: Exception) {}
                var html = p.html
                if (html != null) {
                    for ((cid, part) in p.inline) {
                        try {
                            if (part.size in 0..1_500_000 || part.size < 0) {
                                val bytes = part.inputStream.readBytes()
                                if (bytes.size < 1_500_000) {
                                    val ct = part.contentType.substringBefore(';').trim()
                                    html = html!!.replace("cid:$cid", "data:$ct;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    if (html!!.length > 3_000_000) html = null
                }
                val fr = addr(m.from)
                val fromAddr = fr?.address ?: ""
                val fromName = fr?.personal ?: fromAddr
                val auth = (m.getHeader("Authentication-Results") ?: emptyArray()).joinToString(" ")
                val rt = addr(m.replyTo)?.address
                val sc = Spam.score(fromName, fromAddr, rt, m.subject ?: "", auth, rules)
                val atts = JSONArray()
                p.atts.forEachIndexed { i, a ->
                    val name = try { a.fileName?.let { MimeUtility.decodeText(it) } } catch (_: Exception) { null } ?: "anhang-${i + 1}"
                    atts.put(JSONObject().put("i", i).put("filename", name).put("size", maxOf(0, a.size)).put("contentType", a.contentType.substringBefore(';')))
                }
                val unsub = (m.getHeader("List-Unsubscribe") ?: emptyArray()).joinToString(",")
                val unsubUrl = Regex("<(https://[^>]+)>").find(unsub)?.groupValues?.get(1) ?: Regex("<(mailto:[^>]+)>").find(unsub)?.groupValues?.get(1) ?: ""
                val text = p.text ?: html?.let { htmlToText(it) } ?: ""
                JSONObject().put("uid", uid).put("path", path)
                    .put("subject", m.subject ?: "").put("from", fromName).put("fromAddr", fromAddr)
                    .put("replyTo", rt ?: "")
                    .put("to", (m.getRecipients(Message.RecipientType.TO) ?: emptyArray()).joinToString(", ") { it.toString() })
                    .put("cc", (m.getRecipients(Message.RecipientType.CC) ?: emptyArray()).joinToString(", ") { it.toString() })
                    .put("date", (m.sentDate ?: im.receivedDate ?: Date()).time)
                    .put("messageId", (m as MimeMessage).messageID ?: "")
                    .put("references", JSONArray((m.getHeader("References") ?: emptyArray()).flatMap { it.split(Regex("\\s+")) }.filter { it.isNotBlank() }))
                    .put("html", html ?: JSONObject.NULL)
                    .put("text", text.take(200_000))
                    .put("attachments", atts)
                    .put("score", sc.score).put("reasons", sc.reasons.toJson()).put("allowed", sc.allowed)
                    .put("unsubscribe", unsubUrl)
                    .also { r -> if ((html?.length ?: 0) < 1_500_000) synchronized(readCache) { readCache[ck] = r } }
            } catch (e: Exception) {
                if (keep) { openRead.remove(acc.getString("id") + slot); try { f.close(false) } catch (_: Exception) {} }
                throw e
            } finally { if (!keep) try { f.close(false) } catch (_: Exception) {} }
        }
    }

    /** Liefert einen Anhang als Bytes. */
    fun attachment(acc: JSONObject, path: String, uid: Long, index: Int): Triple<String, String, ByteArray> {
        return withAcc(acc) { s ->
            val f = s.getFolder(path) as IMAPFolder
            f.open(Folder.READ_ONLY)
            try {
                val m = f.getMessageByUID(uid) ?: throw UserError("Die E-Mail wurde nicht mehr gefunden.")
                val p = Parsed(); walk(m, p)
                val a = p.atts.getOrNull(index) ?: throw UserError("Anhang nicht gefunden.")
                val name = try { a.fileName?.let { MimeUtility.decodeText(it) } } catch (_: Exception) { null } ?: "anhang-${index + 1}"
                Triple(name, a.contentType.substringBefore(';').trim().lowercase(), a.inputStream.readBytes())
            } finally { try { f.close(false) } catch (_: Exception) {} }
        }
    }

    // ---------- Spam-Aufräumen ----------
    /** Spam-Ordner → Papierkorb (beim Start und stündlich). Genau diese Mails werden frühestens nach 55 Minuten
     *  im Papierkorb endgültig gelöscht – alle anderen Mails im Papierkorb bleiben unangetastet. */
    private val cleanLock = Any()
    fun spamCleanupAll(force: Boolean = false) {
        if (store.load().getJSONObject("settings").optBoolean("spamClean", true) == false) return
        synchronized(cleanLock) {
            for (acc in store.accounts()) try { spamCleanup(acc, force) } catch (_: Exception) {}
        }
    }

    private fun spamCleanup(acc: JSONObject, force: Boolean) {
        val id = acc.getString("id")
        val book = store.getJson("spamclean")
        val mine = book.optJSONObject(id) ?: JSONObject()
        val now = System.currentTimeMillis()
        if (!force && now - mine.optLong("last") < 55 * 60_000) return
        val fs = folders(acc, slot = INFO)
        val junk = byRole(fs, "junk") ?: return
        val trash = byRole(fs, "trash") ?: return
        val pending = mine.optJSONArray("ids") ?: JSONArray()
        val keep = JSONArray()
        val due = mutableListOf<String>()
        for (i in 0 until pending.length()) {
            val e = pending.getJSONObject(i)
            if (now - e.optLong("at") >= 55 * 60_000) due += e.getString("mid") else keep.put(e)
        }
        val t0 = android.os.SystemClock.elapsedRealtime()
        var deleted = 0; var moved = 0
        val st = connectNew(acc, store.password(id))
        try {
            // 1) früher verschobene Spam-Mails im Papierkorb endgültig löschen (nur genau diese, erkannt an der Message-ID)
            if (due.isNotEmpty()) {
                val f = st.getFolder(trash) as IMAPFolder
                f.open(Folder.READ_WRITE)
                try {
                    for (chunk in due.chunked(40)) {
                        val term: javax.mail.search.SearchTerm = if (chunk.size == 1) javax.mail.search.MessageIDTerm(chunk[0])
                            else javax.mail.search.OrTerm(chunk.map { javax.mail.search.MessageIDTerm(it) }.toTypedArray())
                        val found = f.search(term)
                        if (found.isNotEmpty()) { f.setFlags(found, Flags(Flags.Flag.DELETED), true); deleted += found.size }
                    }
                    if (deleted > 0) f.expunge()
                } finally { try { f.close(false) } catch (_: Exception) {} }
            }
            // 2) aktuellen Inhalt des Spam-Ordners in den Papierkorb schieben und merken
            val j = st.getFolder(junk) as IMAPFolder
            j.open(Folder.READ_WRITE)
            try {
                val msgs = j.messages
                if (msgs.isNotEmpty()) {
                    val fp = FetchProfile(); fp.add(FetchProfile.Item.ENVELOPE); j.fetch(msgs, fp)
                    val withId = mutableListOf<Message>(); val noId = mutableListOf<Message>()
                    for (m in msgs) {
                        val mid = try { (m as MimeMessage).messageID } catch (_: Exception) { null }
                        if (mid.isNullOrBlank()) noId += m else { withId += m; keep.put(JSONObject().put("mid", mid).put("at", now)) }
                    }
                    if (withId.isNotEmpty()) { moveIn(st, j, withId.toTypedArray(), trash); moved += withId.size }
                    // ohne Message-ID ließe sich die Mail im Papierkorb nicht sicher wiederfinden → direkt endgültig löschen
                    if (noId.isNotEmpty()) { j.setFlags(noId.toTypedArray(), Flags(Flags.Flag.DELETED), true); j.expunge(); deleted += noId.size }
                }
            } finally { try { j.close(false) } catch (_: Exception) {} }
        } finally { try { st.close() } catch (_: Exception) {} }
        mine.put("last", now).put("ids", keep)
        book.put(id, mine); store.putJson("spamclean", book)
        if (moved + deleted > 0) Diag.timing("spam", (acc.optString("name").ifBlank { acc.optString("email") }) + " · $moved verschoben, $deleted gelöscht",
            android.os.SystemClock.elapsedRealtime() - t0)
    }

    // ---------- Verschieben / Löschen / Markieren ----------
    private fun moveIn(s: IMAPStore, f: IMAPFolder, msgs: Array<Message>, dest: String) {
        if (msgs.isEmpty()) return
        val d = s.getFolder(dest)
        if (s.hasCapability("MOVE")) f.moveMessages(msgs, d)
        else {
            f.copyMessages(msgs, d)
            f.setFlags(msgs, Flags(Flags.Flag.DELETED), true)
            f.expunge()
        }
    }

    fun moveUids(acc: JSONObject, path: String, uids: List<Long>, dest: String) {
        withAcc(acc) { s ->
            val f = s.getFolder(path) as IMAPFolder
            f.open(Folder.READ_WRITE)
            try { moveIn(s, f, f.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray(), dest) }
            finally { try { f.close(false) } catch (_: Exception) {} }
        }
    }

    fun bulk(op: String, items: JSONArray, dest: JSONObject?, learn: Boolean): JSONObject {
        val cfg = store.load()
        if (learn && (op == "spam" || op == "notspam")) {
            val senders = (0 until items.length()).map { items.getJSONObject(it).optString("fromAddr").lowercase() }.filter { it.isNotBlank() }
            val rules = cfg.getJSONObject("rules")
            val add = if (op == "spam") "block" else "allow"
            val del = if (op == "spam") "allow" else "block"
            rules.put(add, (rules.getJSONArray(add).strings() + senders).distinct().toJson())
            rules.put(del, rules.getJSONArray(del).strings().filter { it !in senders }.toJson())
            store.save(cfg)
        }
        val groups = linkedMapOf<String, LinkedHashMap<String, MutableList<Long>>>()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            groups.getOrPut(it.getString("acc")) { linkedMapOf() }.getOrPut(it.getString("path")) { mutableListOf() } += it.getLong("uid")
        }
        var done = 0
        val errors = JSONArray()
        for ((accId, byPath) in groups) {
            try {
                val acc = store.account(accId)
                val fs = folders(acc)
                val trash = byRole(fs, "trash"); val junk = byRole(fs, "junk")
                withAcc(acc) { s ->
                    for ((path, uids) in byPath) {
                        val f = s.getFolder(path) as IMAPFolder
                        f.open(Folder.READ_WRITE)
                        try {
                            val msgs = f.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
                            val role = roleOf(fs, path)
                            when (op) {
                                "delete" -> if (role == "trash" || trash == null) { f.setFlags(msgs, Flags(Flags.Flag.DELETED), true); f.expunge() } else moveIn(s, f, msgs, trash)
                                "purge" -> { f.setFlags(msgs, Flags(Flags.Flag.DELETED), true); f.expunge() }
                                "spam" -> { if (junk == null) throw UserError("Kein Spam-Ordner gefunden."); if (path != junk) moveIn(s, f, msgs, junk) }
                                "notspam" -> if (!path.equals("INBOX", true)) moveIn(s, f, msgs, "INBOX")
                                "move" -> {
                                    val d = dest?.takeIf { it.optString("acc") == accId }?.optString("path") ?: throw UserError("Mails lassen sich nur innerhalb desselben Kontos verschieben.")
                                    if (d != path) moveIn(s, f, msgs, d)
                                }
                                "seen" -> f.setFlags(msgs, Flags(Flags.Flag.SEEN), true)
                                "unseen" -> f.setFlags(msgs, Flags(Flags.Flag.SEEN), false)
                                "flag" -> f.setFlags(msgs, Flags(Flags.Flag.FLAGGED), true)
                                "unflag" -> f.setFlags(msgs, Flags(Flags.Flag.FLAGGED), false)
                                else -> throw UserError("Unbekannte Aktion.")
                            }
                            done += msgs.size
                        } finally { try { f.close(false) } catch (_: Exception) {} }
                    }
                }
            } catch (e: Exception) { errors.put(friendly(e, null).message) }
        }
        return JSONObject().put("ok", errors.length() == 0).put("done", done).put("errors", errors).put("rules", store.load().getJSONObject("rules"))
    }

    fun empty(acc: JSONObject, path: String): Int {
        val fs = folders(acc)
        val role = roleOf(fs, path)
        if (role != "trash" && role != "junk") throw UserError("Leeren geht nur für Papierkorb und Spam.")
        val trash = byRole(fs, "trash")
        return withAcc(acc) { s ->
            val f = s.getFolder(path) as IMAPFolder
            f.open(Folder.READ_WRITE)
            try {
                val msgs = f.messages
                if (msgs.isNotEmpty()) {
                    if (role == "junk" && trash != null) moveIn(s, f, msgs, trash)
                    else { f.setFlags(msgs, Flags(Flags.Flag.DELETED), true); f.expunge() }
                }
                msgs.size
            } finally { try { f.close(false) } catch (_: Exception) {} }
        }
    }

    // ---------- Senden ----------
    private fun smtpSession(acc: JSONObject): Pair<Session, String> {
        val port = acc.optInt("smtpPort", 465)
        val ssl = port == 465
        val proto = if (ssl) "smtps" else "smtp"
        val p = Properties()
        p["mail.$proto.host"] = acc.optString("smtpHost")
        p["mail.$proto.port"] = port.toString()
        p["mail.$proto.auth"] = "true"
        p["mail.$proto.connectiontimeout"] = "15000"
        p["mail.$proto.timeout"] = "30000"
        p["mail.$proto.writetimeout"] = "30000"
        if (ssl) p["mail.$proto.ssl.enable"] = "true" else { p["mail.$proto.starttls.enable"] = "true"; p["mail.$proto.starttls.required"] = "true" }
        return Session.getInstance(p) to proto
    }

    fun testSmtp(acc: JSONObject, pass: String) {
        val (sess, proto) = smtpSession(acc)
        val t = sess.getTransport(proto)
        try { t.connect(acc.optString("smtpHost"), acc.optInt("smtpPort", 465), user(acc), pass) }
        catch (e: Exception) { throw UserError("Postausgang (SMTP): " + friendly(e, null).message) }
        finally { try { t.close() } catch (_: Exception) {} }
    }

    fun buildMessage(acc: JSONObject, sess: Session, d: JSONObject): MimeMessage {
        val m = MimeMessage(sess)
        m.setFrom(InternetAddress(acc.optString("email"), acc.optString("name"), "UTF-8"))
        fun rcpt(k: String) = d.optString(k).trim().trimEnd(',').takeIf { it.isNotBlank() }?.let { InternetAddress.parse(it, false) }
        rcpt("to")?.let { m.setRecipients(Message.RecipientType.TO, it) }
        rcpt("cc")?.let { m.setRecipients(Message.RecipientType.CC, it) }
        rcpt("bcc")?.let { m.setRecipients(Message.RecipientType.BCC, it) }
        m.setSubject(d.optString("subject"), "UTF-8")
        m.sentDate = Date()
        d.optString("inReplyTo").takeIf { it.isNotBlank() }?.let { m.setHeader("In-Reply-To", it) }
        d.optString("references").takeIf { it.isNotBlank() }?.let { m.setHeader("References", it) }
        // Lesebestätigung anfordern (das Mailprogramm des Empfängers fragt dann, ob es eine Bestätigung schicken soll)
        if (d.optBoolean("receipt")) {
            val me = InternetAddress(acc.optString("email"), acc.optString("name"), "UTF-8").toString()
            m.setHeader("Disposition-Notification-To", me)
            m.setHeader("Return-Receipt-To", me)
        }
        val text = d.optString("text")
        val html = d.optString("html")
        val atts = d.optJSONArray("attachments")
        if (atts == null || atts.length() == 0) {
            if (html.isNotBlank()) m.setContent(altPart(text, html)) else m.setText(text, "UTF-8")
        } else {
            // Text + Anhänge: multipart/mixed
            val mixed = MimeMultipart("mixed")
            mixed.addBodyPart(MimeBodyPart().apply { if (html.isNotBlank()) setContent(altPart(text, html)) else setText(text, "UTF-8") })
            for (i in 0 until atts.length()) {
                val a = atts.getJSONObject(i)
                val f = attFile(a.getString("id"))
                if (!f.exists()) throw UserError("Der Anhang „${a.optString("name")}“ ist nicht mehr vorhanden. Bitte neu anhängen.")
                mixed.addBodyPart(MimeBodyPart().apply {
                    attachFile(f)
                    val mime = a.optString("mime").ifBlank { "application/octet-stream" }
                    setHeader("Content-Type", mime)
                    fileName = MimeUtility.encodeText(a.optString("name").ifBlank { "anhang" }, "UTF-8", null)
                })
            }
            m.setContent(mixed)
        }
        m.saveChanges()
        return m
    }

    private fun altPart(text: String, html: String) = MimeMultipart("alternative").apply {
        addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8") })
        addBodyPart(MimeBodyPart().apply {
            setContent("<!doctype html><html><head><meta charset=\"utf-8\"></head><body style=\"font-family:Arial,sans-serif;font-size:15px;line-height:1.5\">$html</body></html>", "text/html; charset=UTF-8")
        })
    }

    /** Ablage für Anhänge, die mit einer Mail verschickt werden sollen. */
    fun attDir(): java.io.File = java.io.File(MailApp.app.filesDir, "anhaenge").apply { mkdirs() }
    fun attFile(id: String): java.io.File = java.io.File(attDir(), id.replace("/", "").replace("..", ""))

    fun send(d: JSONObject, draft: Boolean) {
        val acc = store.account(d.getString("acc"))
        val (sess, proto) = smtpSession(acc)
        val msg = buildMessage(acc, sess, d)
        if (!draft) {
            if (msg.allRecipients.isNullOrEmpty()) throw UserError("Bitte mindestens einen Empfänger eintragen.")
            val t = sess.getTransport(proto)
            try {
                t.connect(acc.optString("smtpHost"), acc.optInt("smtpPort", 465), user(acc), store.password(acc.getString("id")))
                t.sendMessage(msg, msg.allRecipients)
            } catch (e: Exception) { throw UserError("Senden fehlgeschlagen: " + friendly(e, acc).message) }
            finally { try { t.close() } catch (_: Exception) {} }
        }
        val gmail = acc.optString("imapHost").contains("gmail", true) || acc.optString("imapHost").contains("googlemail", true)
        try {
            val fs = folders(acc)
            val target = byRole(fs, if (draft) "drafts" else "sent")
            if (target != null && (draft || !gmail)) {
                withAcc(acc) { s ->
                    val f = s.getFolder(target)
                    msg.setFlag(Flags.Flag.SEEN, true)
                    if (draft) msg.setFlag(Flags.Flag.DRAFT, true)
                    f.appendMessages(arrayOf(msg))
                }
            }
        } catch (e: Exception) { if (draft) throw friendly(e, acc) }
        // nach dem Versand die Anhang-Kopien aufräumen (Entwürfe behalten sie)
        if (!draft) d.optJSONArray("attachments")?.let { a -> for (i in 0 until a.length()) try { attFile(a.getJSONObject(i).getString("id")).delete() } catch (_: Exception) {} }
        val rp = d.optString("replyPath"); val ru = d.optLong("replyUid", 0)
        if (!draft && rp.isNotBlank() && ru > 0) {
            try {
                withAcc(acc) { s ->
                    val f = s.getFolder(rp) as IMAPFolder
                    f.open(Folder.READ_WRITE)
                    try { f.getMessageByUID(ru)?.setFlag(Flags.Flag.ANSWERED, true) } finally { f.close(false) }
                }
            } catch (_: Exception) {}
        }
    }
}
