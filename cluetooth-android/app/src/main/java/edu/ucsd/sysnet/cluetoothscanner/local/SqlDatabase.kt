package edu.ucsd.sysnet.cluetoothscanner.local

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement

/**
 * The little of SQL the stores need. Production uses [AndroidSqlDatabase]; JVM tests
 * run the same SQL through an in-memory SQLite (JDBC).
 *
 * Android's `rawQuery` binds every argument as text, so [query] arguments may only
 * appear in column comparisons, arithmetic and LIMIT (SQLite applies the column's
 * affinity), never as `MAX(col, ?)`-style value comparisons. The test implementation
 * binds query arguments as text too, so such a mistake fails there as well. Write
 * statements ([execute], [insert]) bind typed values. SQL stays within SQLite 3.9
 * (API 24): no UPSERT, window functions or RETURNING.
 */
interface SqlDatabase : AutoCloseable {
    /** Runs a statement; returns the number of rows changed. Args: Long, Int, Double, String, ByteArray, null. */
    fun execute(sql: String, args: List<Any?> = emptyList()): Int

    /** Runs an INSERT; returns the new rowid. */
    fun insert(sql: String, args: List<Any?> = emptyList()): Long

    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (SqlRow) -> T): List<T>

    /** All-or-nothing; nested calls join the outer transaction. Not thread-affine, but writers must be serialized by the caller. */
    fun <T> transaction(block: () -> T): T
}

interface SqlRow {
    fun long(index: Int): Long
    fun longOrNull(index: Int): Long?
    fun double(index: Int): Double
    fun doubleOrNull(index: Int): Double?
    fun string(index: Int): String
    fun stringOrNull(index: Int): String?
    fun blob(index: Int): ByteArray
}

class AndroidSqlDatabase private constructor(private val db: SQLiteDatabase) : SqlDatabase {
    override fun execute(sql: String, args: List<Any?>): Int =
        db.compileStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeUpdateDelete()
        }

    override fun insert(sql: String, args: List<Any?>): Long =
        db.compileStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeInsert()
        }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> =
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
            val row = CursorRow(cursor)
            buildList { while (cursor.moveToNext()) add(map(row)) }
        }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = db.close()

    private fun bind(statement: SQLiteStatement, args: List<Any?>) {
        args.forEachIndexed { i, value ->
            val index = i + 1
            when (value) {
                null -> statement.bindNull(index)
                is Long -> statement.bindLong(index, value)
                is Int -> statement.bindLong(index, value.toLong())
                is Double -> statement.bindDouble(index, value)
                is String -> statement.bindString(index, value)
                is ByteArray -> statement.bindBlob(index, value)
                else -> throw IllegalArgumentException("Unsupported SQL argument ${value::class}")
            }
        }
    }

    private class CursorRow(private val c: Cursor) : SqlRow {
        override fun long(index: Int) = c.getLong(index)
        override fun longOrNull(index: Int) = if (c.isNull(index)) null else c.getLong(index)
        override fun double(index: Int) = c.getDouble(index)
        override fun doubleOrNull(index: Int) = if (c.isNull(index)) null else c.getDouble(index)
        override fun string(index: Int): String = c.getString(index)
        override fun stringOrNull(index: Int): String? = if (c.isNull(index)) null else c.getString(index)
        override fun blob(index: Int): ByteArray = c.getBlob(index)
    }

    companion object {
        const val FILE_NAME = "local-data.db"

        fun open(context: Context, name: String = FILE_NAME): AndroidSqlDatabase {
            val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, LocalSchema.VERSION) {
                override fun onConfigure(db: SQLiteDatabase) {
                    db.enableWriteAheadLogging()
                }

                override fun onCreate(db: SQLiteDatabase) {
                    LocalSchema.CREATE.forEach(db::execSQL)
                }

                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    LocalSchema.upgrade(oldVersion).forEach(db::execSQL)
                }
            }
            return AndroidSqlDatabase(helper.writableDatabase)
        }
    }
}
