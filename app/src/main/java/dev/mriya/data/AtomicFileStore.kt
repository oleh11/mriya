package dev.mriya.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Atomic file persistence with `.bak` recovery copy.
 * Before replacing an existing [file], its current contents are preserved in `<file>.bak`
 * so a corrupted primary file falls back cleanly to the last known good snapshot.
 */
object AtomicFileStore {
    fun backupFile(file: File): File = File(file.path + ".bak")

    fun writeAtomicWithBackup(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        if (file.exists()) {
            val bakTmp = File(file.path + ".bak.tmp")
            val bak = backupFile(file)
            FileOutputStream(bakTmp).use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                out.fd.sync()
            }
            moveReplacing(bakTmp, bak)
        }
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        moveReplacing(tmp, file)
    }

    fun <T> readWithBackup(file: File, parse: (String) -> T): T? {
        if (file.exists()) {
            runCatching { parse(file.readText()) }.getOrNull()?.let { return it }
        }
        val bak = backupFile(file)
        if (bak.exists()) {
            return runCatching { parse(bak.readText()) }.getOrNull()
        }
        return null
    }

    fun deleteWithBackup(file: File) {
        file.delete()
        backupFile(file).delete()
    }

    private fun moveReplacing(src: File, dst: File) {
        try {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
