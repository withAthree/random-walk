package com.randomwalk.app.data

import com.randomwalk.app.data.db.SqlDatabase
import com.randomwalk.app.data.db.StreetStore

/** A single street database (a pack or the cache) wrapped with its [StreetStore]. */
class StreetDatabase(sql: SqlDatabase) {
    val store: StreetStore = StreetStore(sql)

    private val database: SqlDatabase = sql

    fun close() = database.close()
}
