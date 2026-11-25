package dev.mriya.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicFileStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `writes primary and moves previous snapshot to bak on overwrite`() {
        val target = File(temp.root, "passport.json")
        AtomicFileStore.writeAtomicWithBackup(target, """{"v":1}""".toByteArray())
        assertTrue(target.exists())
        assertFalse(AtomicFileStore.backupFile(target).exists())

        AtomicFileStore.writeAtomicWithBackup(target, """{"v":2}""".toByteArray())
        assertEquals("""{"v":2}""", target.readText())
        assertEquals("""{"v":1}""", AtomicFileStore.backupFile(target).readText())
    }

    @Test
    fun `recovers from bak when primary file is corrupted or missing`() {
        val target = File(temp.root, "userdata.json")
        AtomicFileStore.writeAtomicWithBackup(target, """{"ok":1}""".toByteArray())
        AtomicFileStore.writeAtomicWithBackup(target, """{"ok":2}""".toByteArray())

        // Corrupt primary file
        target.writeText("{truncated")
        val recoveredFromCorrupt = AtomicFileStore.readWithBackup(target) { s ->
            check(s.startsWith("{") && s.endsWith("}"))
            s
        }
        assertEquals("""{"ok":1}""", recoveredFromCorrupt)

        // Delete primary file
        assertTrue(target.delete())
        val recoveredFromMissing = AtomicFileStore.readWithBackup(target) { s ->
            check(s.startsWith("{") && s.endsWith("}"))
            s
        }
        assertEquals("""{"ok":1}""", recoveredFromMissing)
    }

    @Test
    fun `returns null when both primary and bak are corrupt and delete clears both`() {
        val target = File(temp.root, "imports.json")
        AtomicFileStore.writeAtomicWithBackup(target, "good-1".toByteArray())
        AtomicFileStore.writeAtomicWithBackup(target, "good-2".toByteArray())

        target.writeText("bad")
        AtomicFileStore.backupFile(target).writeText("bad")
        val res = AtomicFileStore.readWithBackup(target) { s ->
            check(s.startsWith("good"))
            s
        }
        assertNull(res)

        AtomicFileStore.deleteWithBackup(target)
        assertFalse(target.exists())
        assertFalse(AtomicFileStore.backupFile(target).exists())
    }
}
