package com.pocketmind.memory

import android.content.ContentValues
import android.database.Cursor
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteOpenHelper
import com.pocketmind.tools.AppLog

class NoteStore(private val db: SQLiteDatabase) {
    constructor(helper: AppDatabase) : this(helper.writableDatabase)

    init {
        AppLog.databaseOperation("notes", "ensure_schema") {
            db.execSQL("CREATE TABLE IF NOT EXISTS notes(id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, created INTEGER NOT NULL)")
            try { db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5(content)") }
            catch (e: Exception) { AppLog.warn("DB_SCHEMA_FALLBACK", "store=notes reason=fts_unavailable errorType=${e::class.java.simpleName}") }
        }
    }

    data class Note(val id: Long, val content: String, val created: Long)

    fun save(content: String): Note = AppLog.databaseOperation("notes", "save", { "saved=true" }) {
        val clean = content.trim().take(4000)
        require(clean.isNotBlank()) { "Note text is empty" }
        val values = ContentValues().apply {
            put("content", clean)
            put("created", System.currentTimeMillis())
        }
        val id = db.insert("notes", null, values)
        require(id > 0) { "Note could not be saved" }
        try { db.execSQL("INSERT INTO notes_fts(rowid,content) VALUES(?,?)", arrayOf(id, clean)) } catch (_: Exception) { }
        find(id) ?: throw IllegalStateException("Saved note could not be read back")
    }

    fun find(id: Long): Note? = query("SELECT id,content,created FROM notes WHERE id=?", arrayOf(id.toString())).firstOrNull()
    fun list(limit: Int = 50): List<Note> = query("SELECT id,content,created FROM notes ORDER BY id DESC LIMIT ?", arrayOf(limit.coerceIn(0, 200).toString()))

    fun search(query: String, limit: Int = 20): List<Note> = AppLog.databaseOperation("notes", "search", { "matches=${it.size}" }) {
        val clean = query.trim().take(200)
        if (clean.isBlank()) return@databaseOperation emptyList()
        val match = clean.split(Regex("\\s+")).joinToString(" AND ") { "\"${it.replace("\"", "\"\"")}\"*" }
        try {
            query("SELECT n.id,n.content,n.created FROM notes n JOIN notes_fts f ON f.rowid=n.id WHERE notes_fts MATCH ? ORDER BY n.id DESC LIMIT ?", arrayOf(match, limit.coerceIn(0, 200).toString()))
        } catch (e: Exception) {
            AppLog.warn("DB_READ_FALLBACK", "store=notes reason=fts_unavailable errorType=${e::class.java.simpleName}")
            query("SELECT id,content,created FROM notes WHERE content LIKE ? ORDER BY id DESC LIMIT ?", arrayOf("%$clean%", limit.coerceIn(0, 200).toString()))
        }
    }

    private fun query(sql: String, args: Array<String>): List<Note> = AppLog.databaseOperation("notes", "read", { "rows=${it.size}" }) {
        val out = mutableListOf<Note>()
        db.rawQuery(sql, args).use { cursor: Cursor ->
            while (cursor.moveToNext()) out.add(Note(cursor.getLong(0), cursor.getString(1), cursor.getLong(2)))
        }
        out
    }
}
