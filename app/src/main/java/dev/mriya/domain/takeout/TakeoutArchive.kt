package dev.mriya.domain.takeout

import com.google.gson.stream.JsonReader
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream

/**
 * Pure-Kotlin streaming reader for Google Takeout `.zip` archives and standalone Timeline `.json` exports.
 * Entries are streamed directly without buffering whole files in memory.
 */
object TakeoutArchive {
    private const val PROGRESS_STEP = 2_000
    private const val MAX_ZIP_ENTRIES = 5_000

    /**
     * Returns true when a ZIP entry path looks like a Google Location History / Timeline JSON export
     * and not metadata (`Settings.json`, `Tombstones.json`, `Edits`, macOS resource forks, traversal).
     */
    fun shouldReadEntry(name: String): Boolean {
        val path = name.replace('\\', '/').trim().lowercase()
        if (!path.endsWith(".json")) return false
        if (path.startsWith("/") || path.contains("..")) return false
        if (path.contains("__macosx/") || path.contains("/.") || path.startsWith(".")) return false
        if (path.contains("settings") || path.contains("tombstones") || path.contains("edits")) return false
        val base = path.substringAfterLast('/')
        return path.contains("location history") ||
            path.contains("timeline") ||
            path.contains("semantic location") ||
            base == "records.json"
    }

    /** True when the first bytes match the ZIP local-file or empty-archive signature (`PK\x03\x04` or `PK\x05\x06`). */
    fun isZipMagic(header: ByteArray, length: Int): Boolean {
        if (length < 4) return false
        if (header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) return false
        return (header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
            (header[2] == 0x05.toByte() && header[3] == 0x06.toByte())
    }

    /**
     * Streams either a `.zip` archive or a standalone `.json` file into [engine].
     * Returns the number of JSON files read. Throws [CancellationException] if [isCancelled] becomes true.
     */
    fun streamTakeout(
        input: InputStream,
        engine: TakeoutEngine,
        onProgress: (points: Long, files: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Int {
        val bis = BufferedInputStream(input, 65_536)
        bis.mark(8)
        val header = ByteArray(4)
        val read = readUpTo(bis, header)
        bis.reset()

        var files = 0
        var elements = 0L

        fun checkCancelled() {
            if (isCancelled()) throw CancellationException("Import cancelled")
        }

        fun streamSingle(stream: InputStream) {
            engine.beginFile()
            val reader = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
            TakeoutParser.stream(reader, engine) {
                elements++
                if (elements % 256L == 0L) checkCancelled()
                if (elements % PROGRESS_STEP == 0L) onProgress(engine.points, files)
            }
            checkCancelled()
            engine.endFile()
        }

        if (isZipMagic(header, read)) {
            ZipInputStream(bis).use { zis ->
                var entriesSeen = 0
                while (entriesSeen < MAX_ZIP_ENTRIES) {
                    checkCancelled()
                    val entry = zis.nextEntry ?: break
                    entriesSeen++
                    try {
                        if (!entry.isDirectory && shouldReadEntry(entry.name)) {
                            streamSingle(NonClosingInputStream(zis))
                            files++
                            onProgress(engine.points, files)
                        }
                    } finally {
                        zis.closeEntry()
                    }
                }
            }
        } else {
            checkCancelled()
            streamSingle(bis)
            files = 1
            onProgress(engine.points, files)
        }
        return files
    }

    private fun readUpTo(stream: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = stream.read(buf, total, buf.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private class NonClosingInputStream(inner: InputStream) : FilterInputStream(inner) {
        override fun close() {
            // Keep ZipInputStream open for subsequent entries.
        }
    }
}
