package com.randomwalk.app.data.db

interface SqlRow {
    fun long(column: String): Long
    fun int(column: String): Int
    fun double(column: String): Double
    fun string(column: String): String
    fun blob(column: String): ByteArray
}

interface SqlDatabase {
    fun exec(sql: String, args: List<Any> = emptyList())
    fun <T> query(sql: String, args: List<Any> = emptyList(), map: (SqlRow) -> T): List<T>
    fun <T> transaction(block: () -> T): T
    fun close()
}
