package com.pocketmind

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App-private SQLite persistence. It stores only an AES-GCM-encrypted opaque llama sequence-state
 * blob and the imported model path; no plaintext prompt/reply transcript or web response is stored.
 */
class MemoryStore(context: Context) {
    private val helper = StoreDb(context.applicationContext)

    data class LastModel(val path: String, val modelKey: String)

    fun modelKey(file: File): String {
        val identity = "${file.canonicalPath}|${file.length()}|${file.lastModified()}|$LLAMA_CPP_PIN"
        return sha256(identity)
    }

    fun rulesKey(systemPrompt: String): String = sha256(systemPrompt)

    fun loadKv(modelKey: String, rulesKey: String): ByteArray? {
        val db = helper.readableDatabase
        var invalid = false
        val encrypted = db.query(
            "kv_state", arrayOf("state_blob", "updated_at"), "model_key=? AND rules_key=?",
            arrayOf(modelKey, rulesKey), null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else {
                val expired = System.currentTimeMillis() - cursor.getLong(1) > KV_MAX_AGE_MS
                val blob = if (expired || cursor.isNull(0)) null else cursor.getBlob(0)
                if (expired || blob == null || blob.size > MAX_KV_STORAGE_BYTES) invalid = true
                blob?.takeIf { it.size <= MAX_KV_STORAGE_BYTES }
            }
        }
        if (invalid) helper.writableDatabase.delete(
            "kv_state", "model_key=? AND rules_key=?", arrayOf(modelKey, rulesKey)
        )
        val blob = encrypted ?: return null
        val plain = runCatching { decryptKv(blob) }.getOrNull()
        if (plain == null || plain.isEmpty() || plain.size > MAX_KV_BYTES) {
            helper.writableDatabase.delete(
                "kv_state", "model_key=? AND rules_key=?", arrayOf(modelKey, rulesKey)
            )
            return null
        }
        return plain
    }

    /** Returns false when the native cache is too large or secure persistence is unavailable. */
    fun saveKv(modelKey: String, rulesKey: String, state: ByteArray?): Boolean {
        if (state == null || state.isEmpty() || state.size > MAX_KV_BYTES) {
            helper.writableDatabase.delete(
                "kv_state", "model_key=? AND rules_key=?", arrayOf(modelKey, rulesKey)
            )
            return false
        }
        return try {
            val encrypted = encryptKv(state)
            if (encrypted.size > MAX_KV_STORAGE_BYTES) return false
            val values = ContentValues().apply {
                put("model_key", modelKey)
                put("rules_key", rulesKey)
                put("state_blob", encrypted)
                put("updated_at", System.currentTimeMillis())
            }
            helper.writableDatabase.insertWithOnConflict(
                "kv_state", null, values, SQLiteDatabase.CONFLICT_REPLACE
            ) != -1L
        } catch (_: Exception) {
            false
        }
    }

    fun saveLastModel(path: String, modelKey: String) {
        val values = ContentValues().apply {
            put("id", 1)
            put("path", path)
            put("model_key", modelKey)
        }
        helper.writableDatabase.insertWithOnConflict(
            "last_model", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun lastModel(): LastModel? {
        helper.readableDatabase.query(
            "last_model", arrayOf("path", "model_key"), "id=1", null, null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return LastModel(cursor.getString(0), cursor.getString(1))
        }
    }

    fun clearLastModel() {
        helper.writableDatabase.delete("last_model", "id=1", null)
    }

    /** Clears saved conversation state while leaving the imported GGUF path/file untouched. */
    fun clearMemory() {
        helper.writableDatabase.delete("kv_state", null, null)
    }

    fun close() = helper.close()

    private fun encryptKv(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        return ByteArray(2 + iv.size + encrypted.size).also { output ->
            output[0] = KV_FORMAT_VERSION.toByte()
            output[1] = iv.size.toByte()
            System.arraycopy(iv, 0, output, 2, iv.size)
            System.arraycopy(encrypted, 0, output, 2 + iv.size, encrypted.size)
        }
    }

    private fun decryptKv(blob: ByteArray): ByteArray {
        require(blob.size > 2 && blob[0].toInt() == KV_FORMAT_VERSION)
        val ivSize = blob[1].toInt() and 0xff
        require(ivSize in 12..16 && blob.size > 2 + ivSize + GCM_TAG_BYTES)
        val iv = blob.copyOfRange(2, 2 + ivSize)
        val encrypted = blob.copyOfRange(2 + ivSize, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private class StoreDb(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE kv_state (model_key TEXT NOT NULL, rules_key TEXT NOT NULL, state_blob BLOB NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(model_key, rules_key))")
            db.execSQL("CREATE TABLE last_model (id INTEGER PRIMARY KEY CHECK(id=1), path TEXT NOT NULL, model_key TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Drop short-lived prototype caches that stored search-result URLs/page text in plaintext.
            if (oldVersion < 2) {
                db.execSQL("DROP TABLE IF EXISTS search_cache")
                db.execSQL("DROP TABLE IF EXISTS page_cache")
            }
            if (oldVersion < 3) {
                // Preserve the opaque encrypted snapshot while allowing new prompt versions to coexist.
                db.execSQL("CREATE TABLE kv_state_v3 (model_key TEXT NOT NULL, rules_key TEXT NOT NULL, state_blob BLOB NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(model_key, rules_key))")
                db.execSQL("INSERT INTO kv_state_v3 (model_key, rules_key, state_blob, updated_at) SELECT model_key, rules_key, state_blob, updated_at FROM kv_state")
                db.execSQL("DROP TABLE kv_state")
                db.execSQL("ALTER TABLE kv_state_v3 RENAME TO kv_state")
            }
        }

        companion object {
            private const val DATABASE_NAME = "pocketmind_memory.db"
            private const val DATABASE_VERSION = 3
        }
    }

    companion object {
        const val LLAMA_CPP_PIN = "36a0e2ffc0b352b774f8d71c43668b75b3061ef5"
        const val MAX_KV_BYTES = 64 * 1024 * 1024
        private const val MAX_KV_STORAGE_BYTES = MAX_KV_BYTES + 128
        private const val KV_FORMAT_VERSION = 1
        private const val GCM_TAG_BYTES = 16
        private const val KEY_ALIAS = "com.pocketmind.kv-state.aes-gcm.v1"
        private const val KV_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
