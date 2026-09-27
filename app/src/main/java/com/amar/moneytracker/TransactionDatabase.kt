package com.amar.moneytracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A small on-device store. No account or network connection is required. */
class TransactionDatabase(context: Context) : SQLiteOpenHelper(context, "money_tracker.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                amount REAL NOT NULL,
                amount_cents INTEGER NOT NULL CHECK(amount_cents > 0),
                type TEXT NOT NULL CHECK(type IN ('INCOME', 'EXPENSE')),
                category TEXT NOT NULL,
                date_millis INTEGER NOT NULL CHECK(date_millis >= 0),
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                CHECK(length(trim(title)) > 0)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_transactions_date ON transactions(date_millis DESC)")
        db.execSQL("CREATE INDEX idx_transactions_type_date ON transactions(type, date_millis DESC)")
        createPendingTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Keep every existing entry, converting floating point amounts to exact paise.
            db.execSQL("ALTER TABLE transactions ADD COLUMN amount_cents INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE transactions ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE transactions ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE transactions SET amount_cents = ROUND(amount * 100), created_at = date_millis, updated_at = date_millis")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_transactions_date ON transactions(date_millis DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_transactions_type_date ON transactions(type, date_millis DESC)")
        }
        if (oldVersion < 3) createPendingTable(db)
        if (oldVersion < 4) {
            // Version 1 databases retained a required floating-point `amount` column.
            // Keep it populated while the exact integer paise column is authoritative.
            if (!hasColumn(db, "transactions", "amount")) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN amount REAL NOT NULL DEFAULT 0")
            }
            db.execSQL("UPDATE transactions SET amount = amount_cents / 100.0")
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) return true
            false
        }

    private fun createPendingTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS notification_imports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_hash TEXT NOT NULL UNIQUE,
                package_name TEXT NOT NULL,
                raw_text TEXT NOT NULL,
                title TEXT NOT NULL,
                amount_cents INTEGER NOT NULL CHECK(amount_cents > 0),
                type TEXT NOT NULL CHECK(type IN ('INCOME', 'EXPENSE')),
                date_millis INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING', 'ACCEPTED', 'IGNORED')),
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notification_imports_status ON notification_imports(status, created_at DESC)")
    }

    fun getAll(): List<TransactionEntry> = readableDatabase.query(
        "transactions", null, null, null, null, null, "date_millis DESC, id DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    TransactionEntry(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                        amountCents = cursor.getLong(cursor.getColumnIndexOrThrow("amount_cents")),
                        type = EntryType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("type"))),
                        category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                        dateMillis = cursor.getLong(cursor.getColumnIndexOrThrow("date_millis"))
                    )
                )
            }
        }
    }

    fun save(entry: TransactionEntry) {
        val values = ContentValues().apply {
            put("title", entry.title)
            // Kept for compatibility with databases created by the first app version.
            put("amount", entry.amount)
            put("amount_cents", entry.amountCents)
            put("type", entry.type.name)
            put("category", entry.category)
            put("date_millis", entry.dateMillis)
            put("updated_at", System.currentTimeMillis())
            if (entry.id == 0L) put("created_at", System.currentTimeMillis())
        }
        if (entry.id == 0L) writableDatabase.insertOrThrow("transactions", null, values)
        else writableDatabase.update("transactions", values, "id = ?", arrayOf(entry.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("transactions", "id = ?", arrayOf(id.toString()))
    }

    fun addPendingImport(import: PendingImport): Boolean {
        val values = ContentValues().apply {
            put("source_hash", import.sourceHash)
            put("package_name", import.packageName)
            put("raw_text", import.rawText)
            put("title", import.title)
            put("amount_cents", import.amountCents)
            put("type", import.type.name)
            put("date_millis", import.dateMillis)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("notification_imports", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun getPendingImports(): List<PendingImport> = readableDatabase.query(
        "notification_imports", null, "status = ?", arrayOf("PENDING"), null, null, "created_at DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                PendingImport(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    sourceHash = cursor.getString(cursor.getColumnIndexOrThrow("source_hash")),
                    packageName = cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                    rawText = cursor.getString(cursor.getColumnIndexOrThrow("raw_text")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    amountCents = cursor.getLong(cursor.getColumnIndexOrThrow("amount_cents")),
                    type = EntryType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("type"))),
                    dateMillis = cursor.getLong(cursor.getColumnIndexOrThrow("date_millis"))
                )
            )
        }
    }

    fun updatePendingStatus(id: Long, status: String) {
        require(status == "ACCEPTED" || status == "IGNORED")
        writableDatabase.update("notification_imports", ContentValues().apply { put("status", status) }, "id = ?", arrayOf(id.toString()))
    }
}

enum class EntryType { INCOME, EXPENSE }

data class TransactionEntry(
    val id: Long = 0,
    val title: String,
    val amountCents: Long,
    val type: EntryType,
    val category: String,
    val dateMillis: Long
) {
    val amount: Double get() = amountCents / 100.0
}

data class PendingImport(
    val id: Long,
    val sourceHash: String,
    val packageName: String,
    val rawText: String,
    val title: String,
    val amountCents: Long,
    val type: EntryType,
    val dateMillis: Long
) {
    val amount: Double get() = amountCents / 100.0
}
