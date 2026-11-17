package com.randomwalk.app.data.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class PackInstallerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Asset source backed by a real directory, counting [open] calls. */
    private class FakeAssetSource(private val assetRoot: File) : AssetSource {
        var openCount = 0
            private set

        override fun list(directory: String): List<String> =
            File(assetRoot, directory).listFiles()?.map { it.name }?.sorted() ?: emptyList()

        override fun open(path: String): InputStream {
            openCount++
            return File(assetRoot, path).inputStream()
        }
    }

    private fun assetSource(vararg contents: Pair<String, String>): FakeAssetSource {
        val root = temporaryFolder.newFolder("assets")
        for ((name, content) in contents) {
            val file = File(root, name)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        return FakeAssetSource(root)
    }

    @Test
    fun installerCopiesBundledPacksOnce() {
        val source = assetSource(
            "packs/b.db" to "beta",
            "packs/a.db" to "alpha",
        )
        val targetDir = temporaryFolder.newFolder("target")

        val installer = PackInstaller(source, targetDir)
        val first = installer.installBundled()
        val second = installer.installBundled()

        assertEquals(listOf("a.db", "b.db"), first.map { it.name })
        assertEquals(listOf("a.db", "b.db"), second.map { it.name })
        assertEquals("alpha", File(targetDir, "a.db").readText())
        assertEquals("beta", File(targetDir, "b.db").readText())
        assertEquals(2, source.openCount)
    }

    @Test
    fun installerRecreatesMissingTarget() {
        val source = assetSource(
            "packs/a.db" to "alpha",
            "packs/b.db" to "beta",
        )
        val targetDir = temporaryFolder.newFolder("target")
        val installer = PackInstaller(source, targetDir)
        installer.installBundled()

        assertTrue(File(targetDir, "a.db").delete())
        installer.installBundled()

        assertTrue(File(targetDir, "a.db").exists())
        assertEquals("alpha", File(targetDir, "a.db").readText())
        assertEquals("beta", File(targetDir, "b.db").readText())
        assertEquals(3, source.openCount)
    }

    @Test
    fun installerIgnoresNonDbAssets() {
        val source = assetSource(
            "packs/a.db" to "alpha",
            "packs/readme.txt" to "instructions",
        )
        val targetDir = temporaryFolder.newFolder("target")

        PackInstaller(source, targetDir).installBundled()

        assertEquals(listOf("a.db"), targetDir.listFiles()!!.map { it.name })
    }

    @Test
    fun packFilesListsOnlyDbFilesFromBothDirectories() {
        val dirOne = temporaryFolder.newFolder("one")
        val dirTwo = temporaryFolder.newFolder("two")
        File(dirOne, "b.db").writeText("one-b")
        File(dirOne, "note.txt").writeText("x")
        File(dirOne, "sub").mkdirs()
        File(dirTwo, "a.db").writeText("two-a")
        File(dirTwo, "b.db").writeText("two-b")
        File(dirTwo, "other.txt").writeText("y")

        // Listing dirOne twice verifies canonical-path deduplication.
        val files = PackFiles.list(listOf(dirOne, dirOne, dirTwo))

        assertEquals(listOf("a.db", "b.db", "b.db"), files.map { it.name })
        assertTrue(files[1].canonicalPath.startsWith(dirOne.canonicalPath))
        assertTrue(files[2].canonicalPath.startsWith(dirTwo.canonicalPath))
    }
}
