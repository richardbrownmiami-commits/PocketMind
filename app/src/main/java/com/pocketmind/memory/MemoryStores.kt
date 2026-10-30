package com.pocketmind.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import com.pocketmind.tools.AppLog

class AppDatabase(context: Context, databaseName: String = "pocketmind_memory.db") : SQLiteOpenHelper(context, databaseName, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        AppLog.databaseOperation("database", "schema_create") {
        db.execSQL("CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, content TEXT NOT NULL, time INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS topic_state(id INTEGER PRIMARY KEY CHECK(id=1), entity TEXT NOT NULL, kind TEXT NOT NULL, last_question TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS summaries(id INTEGER PRIMARY KEY CHECK(id=1), body TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS facts(id INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT UNIQUE NOT NULL, value TEXT NOT NULL, auto INTEGER NOT NULL DEFAULT 1, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS recall(id INTEGER PRIMARY KEY AUTOINCREMENT, message_id INTEGER, snippet TEXT NOT NULL, features BLOB NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS action_log(id INTEGER PRIMARY KEY AUTOINCREMENT, request TEXT NOT NULL, tool TEXT NOT NULL, args TEXT NOT NULL, outcome TEXT NOT NULL, message TEXT NOT NULL, created INTEGER NOT NULL, source TEXT NOT NULL DEFAULT 'legacy')")
        db.execSQL("CREATE TABLE IF NOT EXISTS notes(id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS feature_version(id INTEGER PRIMARY KEY CHECK(id=1), version INTEGER NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO feature_version(id,version) VALUES(1,1)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_time ON messages(time)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_recall_created ON recall(created)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_actions_created ON action_log(created)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(message_id UNINDEXED, role UNINDEXED, content)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS recall_fts USING fts5(snippet)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5(content)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS action_log_fts USING fts5(action_id UNINDEXED, request, tool, args, outcome, message, source)")
        db.execSQL("INSERT INTO action_log_fts(rowid,action_id,request,tool,args,outcome,message,source) SELECT a.id,a.id,a.request,a.tool,a.args,a.outcome,a.message,a.source FROM action_log a WHERE NOT EXISTS (SELECT 1 FROM action_log_fts f WHERE f.rowid=a.id)")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        AppLog.databaseOperation("database", "schema_upgrade") {
        if (oldVersion < 2) db.execSQL("ALTER TABLE action_log ADD COLUMN source TEXT NOT NULL DEFAULT 'legacy'")
        onCreate(db)
        }
    }
}

private fun Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column))
private fun SQLiteDatabase.forEach(sql: String, args: Array<String>? = null, block: (Cursor) -> Unit) {
    val store = Regex("(?i)\\b(?:FROM|JOIN)\\s+([a-z_][a-z0-9_]*)")
        .find(sql)?.groupValues?.get(1)?.lowercase() ?: "unknown"
    var rows = 0
    AppLog.databaseOperation(store, "read", { "rows=$rows" }) {
        rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                block(cursor)
                rows++
            }
        }
    }
}

class MessageStore(private val db: SQLiteDatabase) {
    constructor(helper: AppDatabase) : this(helper.writableDatabase)

    fun append(role: String, content: String, timeMillis: Long = System.currentTimeMillis()): Long =
        AppLog.databaseOperation("messages", "append", { "inserted=${it > 0L}" }) {
        require(role == "user" || role == "assistant" || role == "system")
        val clean = content.take(20_000)
        val id = db.insert("messages", null, ContentValues().apply {
            put("role", role); put("content", clean); put("time", timeMillis)
        })
        if (id > 0) db.execSQL("INSERT INTO messages_fts(message_id,role,content) VALUES(?,?,?)", arrayOf(id, role, clean))
        id
    }

    fun recent(limit: Int = 8): List<Message> = query("SELECT id,role,content,time FROM messages ORDER BY id DESC LIMIT ?", arrayOf(limit.coerceIn(0, 100).toString())).reversed()
    fun lastExchangeBefore(messageId: Long): List<Message> =
        query("SELECT id,role,content,time FROM messages WHERE id<? AND role IN ('user','assistant') ORDER BY id DESC LIMIT 2", arrayOf(messageId.toString())).reversed()
    fun all(): List<Message> = query("SELECT id,role,content,time FROM messages ORDER BY id")
    fun find(id: Long): Message? = query("SELECT id,role,content,time FROM messages WHERE id=?", arrayOf(id.toString())).firstOrNull()

    private fun query(sql: String, args: Array<String>? = null): List<Message> {
        val result = mutableListOf<Message>()
        db.forEach(sql, args) { c -> result.add(Message(c.getLong(0), c.text("role"), c.text("content"), c.getLong(3))) }
        return result
    }
}

data class Message(val id: Long, val role: String, val content: String, val time: Long)
data class TopicState(val entity: String, val kind: String, val lastQuestion: String, val updated: Long)

class TopicStateStore(private val db: SQLiteDatabase) {
    constructor(helper: AppDatabase) : this(helper.writableDatabase)

    fun set(entity: String, kind: String, lastQuestion: String, nowMillis: Long) {
        AppLog.databaseOperation("topic_state", "upsert") {
        val values = ContentValues().apply {
            put("id", 1); put("entity", entity.take(100)); put("kind", kind.take(40)); put("last_question", lastQuestion.take(500)); put("updated", nowMillis)
        }
        db.insertWithOnConflict("topic_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun current(nowMillis: Long): TopicState? {
        var topic: TopicState? = null
        db.forEach("SELECT entity,kind,last_question,updated FROM topic_state WHERE id=1") { c ->
            topic = TopicState(c.text("entity"), c.text("kind"), c.text("last_question"), c.getLong(3))
        }
        return topic?.takeIf { MemoryRules.topicIsActive(it, nowMillis) } ?: run { clear(); null }
    }

    fun clear() { db.delete("topic_state", null, null) }
}

object FollowUpResolver {
    private val command = Regex("(?i)^\\s*(search|open|launch|call|play|set|remind|timer|alarm|navigate|find|weather|calculate|send|message|turn on|turn off)\\b")
    private val pronoun = Regex("(?i)\\b(he|she|it|they|him|her|this|that)\\b")
    private val hinglish = Regex("(?i)\\b(aur batao|uske baare mein|iske baare mein|woh kya|wo kya|phir kya|and then|what about|tell me more)\\b")

    fun resolve(text: String, topic: TopicState?): String? {
        val clean = text.trim()
        if (clean.isEmpty() || topic == null || command.containsMatchIn(clean)) return null
        val hasPronoun = pronoun.containsMatchIn(clean)
        val hasFollowupPhrase = hinglish.containsMatchIn(clean)
        if (!hasPronoun && !hasFollowupPhrase) return null
        val rewritten = pronoun.replace(clean) { match ->
            if (match.value.equals("he", true) || match.value.equals("she", true) || match.value.equals("it", true) || match.value.equals("they", true)) topic.entity else topic.entity
        }
        return if (hasFollowupPhrase && !hasPronoun) "${topic.entity}: $rewritten" else rewritten
    }
}

class SummaryStore(private val db: SQLiteDatabase) {
    constructor(helper: AppDatabase) : this(helper.writableDatabase)
    fun get(): String? {
        var value: String? = null
        db.forEach("SELECT body FROM summaries WHERE id=1") { value = it.text("body") }
        return value
    }
    fun update(text: String) {
        AppLog.databaseOperation("summaries", "upsert") {
        val values = ContentValues().apply {
            put("id", 1); put("body", MemoryRules.boundedSummary(text, 80)); put("updated", System.currentTimeMillis())
        }
        db.insertWithOnConflict("summaries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
}

data class Fact(val key: String, val value: String, val autoCreated: Boolean = true)

class FactStore(private val db: SQLiteDatabase) {
    constructor(helper: AppDatabase) : this(helper.writableDatabase)

    fun capture(text: String): Boolean = AppLog.databaseOperation("facts", "capture", { "saved=$it" }) {
        val candidate = MemoryRules.extractFact(text) ?: return@databaseOperation false
        val values = ContentValues().apply {
            put("key", candidate.key); put("value", candidate.value); put("auto", 1); put("created", System.currentTimeMillis())
        }
        db.insertWithOnConflict("facts", null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0
    }

    fun all(): List<Fact> {
        val result = mutableListOf<Fact>()
        db.forEach("SELECT key,value,auto FROM facts ORDER BY created DESC") { c -> result.add(Fact(c.text("key"), c.text("value"), c.getInt(2) != 0)) }
        return result
    }

    fun relevant(query: String, limit: Int = 3): List<Fact> {
        val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (terms.isEmpty()) return emptyList()
        val predicate = terms.joinToString(" OR ") { "(lower(key) LIKE ? OR lower(value) LIKE ?)" }
        val args = terms.flatMap { term -> listOf("%$term%", "%$term%") }.toTypedArray()
        val candidates = mutableListOf<Fact>()
        db.forEach("SELECT key,value,auto FROM facts WHERE $predicate ORDER BY created DESC", args) { c ->
            candidates.add(Fact(c.text("key"), c.text("value"), c.getInt(2) != 0))
        }
        return candidates.map { fact -> fact to terms.count { term -> "${fact.key} ${fact.value}".lowercase().contains(term) } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(limit.coerceIn(0, 3)).map { it.first }
    }

    fun forget(query: String): Int = AppLog.databaseOperation("facts", "forget", { "deleted=$it" }) {
        val clean = query.trim().lowercase()
        if (clean.isEmpty()) 0
        else db.delete("facts", "lower(key)=? OR lower(value) LIKE ?", arrayOf(clean, "%$clean%"))
    }

    fun delete(key: String): Boolean = AppLog.databaseOperation("facts", "delete", { "deleted=$it" }) {
        db.delete("facts", "lower(key)=?", arrayOf(key.lowercase())) > 0
    }
    fun clearAutoCreated() {
        AppLog.databaseOperation("facts", "clear_auto_created", { "deleted=$it" }) { db.delete("facts", "auto=1", null) }
    }
}

data class RecallSnippet(val id: Long, val messageId: Long?, val text: String, val created: Long)

class RecallStore(private val db: SQLiteDatabase) {
    constructor(helper: AppDatabase) : this(helper.writableDatabase)

    fun index(messageId: Long?, snippet: String) {
        AppLog.databaseOperation("recall", "index") {
            val clean = snippet.trim().take(700)
            if (clean.isNotEmpty()) {
                val values = ContentValues().apply {
                    if (messageId != null) put("message_id", messageId)
                    put("snippet", clean); put("features", features(clean)); put("created", System.currentTimeMillis())
                }
                val id = db.insert("recall", null, values)
                if (id > 0) db.execSQL("INSERT INTO recall_fts(rowid,snippet) VALUES(?,?)", arrayOf(id, clean))
            }
        }
    }

    fun recall(query: String, currentMessageId: Long?): List<RecallSnippet> {
        val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 1 }.distinct().take(20)
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" OR ") { "$it*" }
        val candidates = mutableListOf<Pair<RecallSnippet, Int>>()
        val args = arrayOf(match)
        try {
            db.forEach("SELECT r.id,r.message_id,r.snippet,r.created,r.features FROM recall r JOIN recall_fts f ON f.rowid=r.id WHERE recall_fts MATCH ? ORDER BY r.created DESC LIMIT 30", args) { c ->
                val mid = if (c.isNull(1)) null else c.getLong(1)
                if (currentMessageId == null || mid != currentMessageId) {
                    val text = c.text("snippet")
                    val textScore = terms.count { text.contains(it, ignoreCase = true) }
                    val featureScore = featureOverlap(query, c.getBlob(4))
                    candidates.add(RecallSnippet(c.getLong(0), mid, text, c.getLong(3)) to (textScore * 2 + featureScore))
                }
            }
        } catch (_: Exception) {
            AppLog.warn("DB_READ_FALLBACK", "store=recall reason=fts_unavailable")
            db.forEach("SELECT id,message_id,snippet,created,features FROM recall ORDER BY created DESC LIMIT 100") { c ->
                val mid = if (c.isNull(1)) null else c.getLong(1)
                if (currentMessageId == null || mid != currentMessageId) {
                    val text = c.text("snippet")
                    val score = terms.count { text.contains(it, ignoreCase = true) } * 2 + featureOverlap(query, c.getBlob(4))
                    if (score > 0) candidates.add(RecallSnippet(c.getLong(0), mid, text, c.getLong(3)) to score)
                }
            }
        }
        val ranked = candidates.sortedByDescending { it.second }.map { it.first }
        return MemoryRules.capRecall(ranked, currentMessageId, 2, 700)
    }

    fun clear() {
        AppLog.databaseOperation("recall", "clear") {
            db.delete("recall", null, null)
            try { db.execSQL("DELETE FROM recall_fts") } catch (_: Exception) { }
        }
    }

    private fun features(text: String): ByteArray {
        val bits = ByteArray(32)
        text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }.forEach { term ->
            val digest = MessageDigest.getInstance("SHA-256").digest(term.toByteArray())
            val index = (((digest[0].toInt() and 0xff) shl 8) or (digest[1].toInt() and 0xff)) % 256
            bits[index / 8] = (bits[index / 8].toInt() or (1 shl (index % 8))).toByte()
        }
        return bits
    }

    private fun featureOverlap(query: String, stored: ByteArray): Int {
        val q = features(query)
        var score = 0
        for (i in 0 until minOf(q.size, stored.size)) score += Integer.bitCount((q[i].toInt() and stored[i].toInt()) and 0xff)
        return score
    }
}

class ActionLogStore(private val db: SQLiteDatabase) {
    constructor(helper: AppDatabase) : this(helper.writableDatabase)

    fun record(request: String, tool: String, argsJson: String, outcome: String, message: String, source: String = "legacy") {
        AppLog.databaseOperation("action_log", "record") {
        val safeArgs = Regex("(?:hour|minute|daysFromToday|seconds|level|step|index|enabled)=([^,}]+)").findAll(argsJson)
            .joinToString(prefix = "{", postfix = "}") { "${it.groupValues[0]}" }.take(500)
        val safeMessage = when (tool) {
            "note.save" -> "Saved note and verified read-back."
            "note.list" -> "Listed saved notes."
            "note.search" -> "Searched saved notes."
            else -> message.take(180)
        }
        val safeSource = source.lowercase().takeIf { it in ACTION_SOURCES } ?: "legacy"
        val values = ContentValues().apply {
            put("request", request.trim().take(500)); put("tool", tool.take(100)); put("args", safeArgs)
            put("outcome", outcome.take(180)); put("message", safeMessage); put("created", System.currentTimeMillis()); put("source", safeSource)
        }
        db.beginTransaction()
        try {
            val id = db.insert("action_log", null, values)
            check(id > 0L) { "SQLite did not insert the action log row" }
            db.execSQL("INSERT INTO action_log_fts(rowid,action_id,request,tool,args,outcome,message,source) VALUES(?,?,?,?,?,?,?,?)", arrayOf(id, id, values.getAsString("request"), values.getAsString("tool"), safeArgs, values.getAsString("outcome"), safeMessage, safeSource))
            db.execSQL("DELETE FROM action_log WHERE id NOT IN (SELECT id FROM action_log ORDER BY id DESC LIMIT 200)")
            db.execSQL("DELETE FROM action_log_fts WHERE rowid NOT IN (SELECT id FROM action_log)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        }
    }

    fun recent(limit: Int = 200): List<ActionRecord> {
        return readRows("SELECT id,request,tool,args,outcome,message,created,source FROM action_log ORDER BY id DESC LIMIT ?", arrayOf(limit.coerceIn(0, 200).toString()))
    }

    fun search(query: String? = null, limit: Int = 10, source: String? = null): List<ActionRecord> {
        val boundedLimit = limit.coerceIn(1, 20)
        val safeSource = source?.lowercase()?.takeIf { it in ACTION_SOURCES }
        val terms = query.orEmpty().lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.distinct().take(12)
        if (terms.isEmpty()) return recent(200).filter { safeSource == null || it.source == safeSource }.take(boundedLimit)

        val match = terms.joinToString(" OR ") { "\"$it\"*" }
        val sql = buildString {
            append("SELECT a.id,a.request,a.tool,a.args,a.outcome,a.message,a.created,a.source FROM action_log_fts JOIN action_log a ON a.id=action_log_fts.rowid WHERE action_log_fts MATCH ?")
            if (safeSource != null) append(" AND a.source=?")
            append(" ORDER BY a.id DESC LIMIT ?")
        }
        val args = buildList {
            add(match)
            if (safeSource != null) add(safeSource)
            add(boundedLimit.toString())
        }.toTypedArray()
        return try {
            readRows(sql, args)
        } catch (_: Exception) {
            recent(200).filter { row ->
                (safeSource == null || row.source == safeSource) && terms.any { term ->
                    "${row.request} ${row.tool} ${row.argsJson} ${row.outcome} ${row.message}".contains(term, ignoreCase = true)
                }
            }.take(boundedLimit)
        }
    }

    private fun readRows(sql: String, args: Array<String>?): List<ActionRecord> {
        val result = mutableListOf<ActionRecord>()
        db.forEach(sql, args) { c ->
            result.add(ActionRecord(c.getLong(0), c.text("request"), c.text("tool"), c.text("args"), c.text("outcome"), c.text("message"), c.getLong(6), c.text("source")))
        }
        return result
    }

    companion object {
        private val ACTION_SOURCES = setOf("rules", "planner", "router", "legacy", "unknown")
    }
}

data class ActionRecord(val id: Long, val request: String, val tool: String, val argsJson: String, val outcome: String, val message: String, val created: Long, val source: String)

fun clearMemory(db: SQLiteDatabase) {
    AppLog.databaseOperation("memory", "clear") {
        RecallStore(db).clear()
        AppLog.databaseOperation("messages", "clear") {
            db.delete("messages", null, null)
            try { db.execSQL("DELETE FROM messages_fts") } catch (_: Exception) { }
        }
        AppLog.databaseOperation("summaries", "clear") { db.delete("summaries", null, null) }
        AppLog.databaseOperation("topic_state", "clear") { db.delete("topic_state", null, null) }
        FactStore(db).clearAutoCreated()
        AppLog.databaseOperation("action_log", "clear") {
            db.delete("action_log", null, null)
            try { db.execSQL("DELETE FROM action_log_fts") } catch (_: Exception) { }
        }
    }
}
fun clearMemory(helper: AppDatabase) = clearMemory(helper.writableDatabase)
