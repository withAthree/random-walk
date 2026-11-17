package com.randomwalk.app.data.pack

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException

/**
 * Copies bundled `.db` packs from assets into [targetDir] so they can be opened
 * read-only. Existing targets are left untouched, so packs are written once.
 */
class PackInstaller(
    private val assetSource: AssetSource,
    private val targetDir: File,
) {
    fun installBundled(): List<File> {
        val installed = ArrayList<File>()
        for (name in assetSource.list("packs").filter { it.endsWith(".db") }.sorted()) {
            val target = File(targetDir, name)
            if (target.exists()) {
                installed.add(target)
                continue
            }

            targetDir.mkdirs()
            val temp = File(targetDir, "$name.tmp")
            try {
                assetSource.open("packs/$name").use { input ->
                    BufferedOutputStream(temp.outputStream()).use { output ->
                        input.copyTo(output)
                    }
                }
                if (!temp.renameTo(target)) {
                    throw IOException("Failed to move ${temp.name} into place")
                }
                installed.add(target)
            } catch (e: IOException) {
                temp.delete()
                throw e
            }
        }
        return installed.sortedBy { it.name }
    }
}
