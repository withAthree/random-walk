package com.randomwalk.app.data.pack

import java.io.File

/** Lists the `.db` pack files across [dirs], deduplicated and sorted. */
object PackFiles {
    fun list(dirs: List<File?>): List<File> {
        val byCanonicalPath = LinkedHashMap<String, File>()
        for (dir in dirs) {
            if (dir == null) continue
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (file.isFile && file.name.endsWith(".db")) {
                    byCanonicalPath.putIfAbsent(file.canonicalPath, file)
                }
            }
        }
        return byCanonicalPath.values.sortedWith(compareBy({ it.name }, { it.absolutePath }))
    }
}
