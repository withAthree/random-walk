package com.randomwalk.app.data.db

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Android [SqlDatabase] adapter over the framework [SQLiteDatabase]. */
class AndroidSqlDatabase(private val database: SQLiteDatabase) : SqlDatabase {

    override fun exec(sql: String, args: List<Any>) {
        database.execSQL(sql, args.toTypedArray())
    }

    override fun <T> query(sql: String, args: List<Any>, map: (SqlRow) -> T): List<T> {
        val cursor = database.rawQuery(sql, args.map { it.toString() }.toTypedArray())
        return cursor.use { c ->
            val results = ArrayList<T>()
            while (c.moveToNext()) {
                results.add(map(AndroidSqlRow(c)))
            }
            results
        }
    }

    override fun <T> transaction(block: () -> T): T {
        database.beginTransaction()
        return try {
            val result = block()
            database.setTransactionSuccessful()
            result
        } finally {
            database.endTransaction()
        }
    }

    override fun close() {
        database.close()
    }

    companion object {
        fun openWritable(context: Context, name: String): AndroidSqlDatabase =
            AndroidSqlDatabase(context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null))

        fun openReadOnly(file: File): AndroidSqlDatabase =
            AndroidSqlDatabase(
                SQLiteDatabase.openDatabase(
                    file.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ),
            )
    }
}

private class AndroidSqlRow(private val cursor: Cursor) : SqlRow {
    override fun long(column: String): Long = cursor.getLong(cursor.getColumnIndexOrThrow(column))
    override fun int(column: String): Int = cursor.getInt(cursor.getColumnIndexOrThrow(column))
    override fun double(column: String): Double = cursor.getDouble(cursor.getColumnIndexOrThrow(column))
    override fun string(column: String): String = cursor.getString(cursor.getColumnIndexOrThrow(column))
    override fun blob(column: String): ByteArray = cursor.getBlob(cursor.getColumnIndexOrThrow(column))
}
