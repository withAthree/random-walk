package com.randomwalk.app.data

import android.content.Context
import android.util.Log
import com.randomwalk.app.data.db.AndroidSqlDatabase
import com.randomwalk.app.data.db.StreetStore
import com.randomwalk.app.data.pack.AndroidAssetSource
import com.randomwalk.app.data.pack.PackFiles
import com.randomwalk.app.data.pack.PackInstaller
import java.io.File

/**
 * Opens every available street-data source: bundled packs (read-only) plus the
 * local cache (writable). The cache backs [cacheStore]; [repository] reads packs
 * first and the cache last.
 */
class StreetDatabaseProvider private constructor(
    private val packDatabases: List<StreetDatabase>,
    private val cacheDatabase: StreetDatabase,
) {
    val cacheStore: StreetStore get() = cacheDatabase.store

    val repository: StreetRepository = StreetRepository(packDatabases + cacheDatabase)

    fun close() {
        packDatabases.forEach { it.close() }
        cacheDatabase.close()
    }

    companion object {
        private const val TAG = "StreetDatabaseProvider"

        fun open(context: Context): StreetDatabaseProvider {
            val packDir = File(context.filesDir, "packs")
            PackInstaller(AndroidAssetSource(context.assets), packDir).installBundled()

            val packFiles = PackFiles.list(
                listOf(packDir, context.getExternalFilesDir("packs")),
            )

            val packDatabases = ArrayList<StreetDatabase>()
            for (file in packFiles) {
                var database: StreetDatabase? = null
                try {
                    database = StreetDatabase(AndroidSqlDatabase.openReadOnly(file))
                    database.store.verifySchema()
                    packDatabases.add(database)
                } catch (e: Exception) {
                    database?.close()
                    Log.w(TAG, "Skipping pack ${file.name}: ${e.message}", e)
                }
            }

            val cacheDatabase = StreetDatabase(
                AndroidSqlDatabase.openWritable(context, "street_cache"),
            )
            cacheDatabase.store.ensureSchema()

            return StreetDatabaseProvider(packDatabases, cacheDatabase)
        }
    }
}
