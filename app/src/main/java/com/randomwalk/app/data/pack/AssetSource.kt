package com.randomwalk.app.data.pack

import android.content.res.AssetManager
import java.io.InputStream

/** Read-only view over bundled assets, kept behind an interface for testability. */
interface AssetSource {
    fun list(directory: String): List<String>
    fun open(path: String): InputStream
}

/** [AssetSource] backed by Android's [AssetManager]. */
class AndroidAssetSource(private val assets: AssetManager) : AssetSource {

    override fun list(directory: String): List<String> =
        assets.list(directory)?.toList() ?: emptyList()

    override fun open(path: String): InputStream = assets.open(path)
}
