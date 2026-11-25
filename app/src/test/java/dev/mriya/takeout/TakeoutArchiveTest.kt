package dev.mriya.takeout

import dev.mriya.data.LatLon
import dev.mriya.domain.haversineKm
import dev.mriya.domain.takeout.TakeoutArchive
import dev.mriya.domain.takeout.TakeoutEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TakeoutArchiveTest {
    private val berlin = LatLon(52.36, 13.50)
    private val london = LatLon(51.47, -0.45)

    private fun newEngine(): TakeoutEngine = TakeoutEngine(
        isoAt = { _, lon -> if (lon > 5.0) "DE" else "GB" },
        airportNear = { p -> listOf(berlin to "BER", london to "LHR").firstOrNull { haversineKm(p, it.first) < 25 }?.second },
        airportCountry = { p ->
            listOf(berlin to "DE", london to "GB").firstOrNull { haversineKm(p, it.first) < 40 }?.second
        },
    )

    @Test
    fun `entry filter accepts location exports and skips settings tombstones edits`() {
        assertTrue(TakeoutArchive.shouldReadEntry("Takeout/Location History/Records.json"))
        assertTrue(TakeoutArchive.shouldReadEntry("Takeout/Location History/Semantic Location History/2025/2025_MAY.json"))
        assertTrue(TakeoutArchive.shouldReadEntry("Takeout/Timeline/Timeline.json"))
        assertTrue(TakeoutArchive.shouldReadEntry("Records.json"))

        assertFalse(TakeoutArchive.shouldReadEntry("Takeout/Location History/Settings.json"))
        assertFalse(TakeoutArchive.shouldReadEntry("Takeout/Location History/Tombstones.json"))
        assertFalse(TakeoutArchive.shouldReadEntry("Takeout/Location History/Timeline Edits.json"))
        assertFalse(TakeoutArchive.shouldReadEntry("__MACOSX/Takeout/Location History/._Records.json"))
        assertFalse(TakeoutArchive.shouldReadEntry("Takeout/Location History/image.png"))
        assertFalse(TakeoutArchive.shouldReadEntry("../Takeout/Location History/Records.json"))
        assertFalse(TakeoutArchive.shouldReadEntry("/Takeout/Location History/Records.json"))
    }

    @Test
    fun `streams multi-entry zip and ignores non-location entries`() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("Takeout/Location History/Settings.json"))
            zos.write("""{"locations":[{"latitudeE7":0,"longitudeE7":0,"timestamp":"2020-01-01T00:00:00Z"}]}""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Takeout/Location History/Tombstones.json"))
            zos.write("""{"locations":[{"latitudeE7":0,"longitudeE7":0,"timestamp":"2020-01-01T00:00:00Z"}]}""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Takeout/Location History/Records.json"))
            zos.write(
                """{"locations":[
                    {"latitudeE7":523600000,"longitudeE7":135000000,"timestamp":"2024-04-10T08:00:00Z"},
                    {"latitudeE7":514700000,"longitudeE7":-4500000,"timestamp":"2024-04-10T10:15:00Z"}
                ]}""".toByteArray()
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Takeout/Location History/Semantic Location History/2025/2025_MAY.json"))
            zos.write(
                """{"timelineObjects":[
                    {"activitySegment":{
                        "startLocation":{"latitudeE7":514700000,"longitudeE7":-4500000},
                        "endLocation":{"latitudeE7":523600000,"longitudeE7":135000000},
                        "duration":{"startTimestamp":"2025-05-02T12:00:00Z","endTimestamp":"2025-05-02T14:00:00Z"},
                        "activityType":"FLYING"
                    }}
                ]}""".toByteArray()
            )
            zos.closeEntry()
        }

        val engine = newEngine()
        val files = TakeoutArchive.streamTakeout(ByteArrayInputStream(baos.toByteArray()), engine)
        val res = engine.result(100L)

        assertEquals(2, files)
        assertEquals(4L, res.points)
        assertEquals(2, res.flights.size)
        assertEquals(setOf("DE", "GB"), res.stamps.keys)
    }

    @Test
    fun `streams plain json file without zip wrapper`() {
        val json = """{"semanticSegments":[
            {"startTime":"2025-03-12T08:00:00Z","endTime":"2025-03-12T10:00:00Z",
             "activity":{"start":{"latLng":"52.36°, 13.50°"},"end":{"latLng":"51.47°, -0.45°"},"topCandidate":{"type":"FLYING"}}}
        ]}"""
        val engine = newEngine()
        val files = TakeoutArchive.streamTakeout(ByteArrayInputStream(json.toByteArray()), engine)
        val res = engine.result(50L)

        assertEquals(1, files)
        assertEquals(1, res.flights.size)
        assertEquals(setOf("DE", "GB"), res.stamps.keys)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation stops mid-stream`() {
        val engine = newEngine()
        val stream = SyntheticRecordsStream(5_000)
        TakeoutArchive.streamTakeout(stream, engine, isCancelled = { engine.points >= 512L })
    }

    @Test
    fun `streams one million synthetic points within memory limits`() {
        val engine = TakeoutEngine(
            isoAt = { _, _ -> "DE" },
            airportNear = { null },
            airportCountry = { null },
        )
        var progressCalls = 0
        val files = TakeoutArchive.streamTakeout(
            input = SyntheticRecordsStream(1_000_000),
            engine = engine,
            onProgress = { _, _ -> progressCalls++ },
        )
        val res = engine.result(1L)

        assertEquals(1, files)
        assertEquals(1_000_000L, res.points)
        assertTrue(progressCalls >= 500)
        assertEquals(setOf("DE"), res.stamps.keys)
    }

    /** Generates a valid `Records.json` stream on the fly without materializing millions of records in memory. */
    private class SyntheticRecordsStream(private val count: Int) : InputStream() {
        private val prefix = "{\"locations\":[".toByteArray()
        private val suffix = "]}".toByteArray()
        private var stage = 0
        private var index = 0
        private var cur = prefix
        private var pos = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var written = 0
            while (written < len) {
                if (pos >= cur.size) {
                    if (!advance()) break
                }
                val chunk = minOf(len - written, cur.size - pos)
                System.arraycopy(cur, pos, b, off + written, chunk)
                pos += chunk
                written += chunk
            }
            return if (written == 0) -1 else written
        }

        private fun advance(): Boolean {
            pos = 0
            return when (stage) {
                0 -> {
                    stage = 1
                    if (count == 0) {
                        stage = 2
                        cur = suffix
                    } else {
                        cur = recordBytes(0)
                    }
                    true
                }
                1 -> {
                    index++
                    if (index < count) {
                        cur = recordBytes(index)
                        true
                    } else {
                        stage = 2
                        cur = suffix
                        true
                    }
                }
                else -> false
            }
        }

        private fun recordBytes(i: Int): ByteArray {
            val comma = if (i > 0) "," else ""
            val ts = 1_700_000_000L + (i % 86_400)
            return """$comma{"latitudeE7":523600000,"longitudeE7":135000000,"timestampMs":"${ts * 1000}"}""".toByteArray()
        }
    }
}
