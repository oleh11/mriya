package dev.mriya.imports

import com.google.zxing.BarcodeFormat
import com.google.zxing.aztec.AztecWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.pdf417.PDF417Writer
import dev.mriya.domain.imports.BarcodeDecoder
import dev.mriya.domain.imports.FlightExtractor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Generates real PDF417 / Aztec barcodes of the IATA sample boarding pass and reads them back. */
class BarcodeDecoderTest {
    private val bcbp = "M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 226F001A0025 100"

    private fun pixels(m: BitMatrix, scale: Int, invert: Boolean = false): Triple<Int, Int, IntArray> {
        val quiet = 4 * scale
        val w = m.width * scale + 2 * quiet; val h = m.height * scale + 2 * quiet
        val bg = if (invert) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val fg = if (invert) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val px = IntArray(w * h) { bg }
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y))
            for (dy in 0 until scale) for (dx in 0 until scale) px[(quiet + y * scale + dy) * w + quiet + x * scale + dx] = fg
        return Triple(w, h, px)
    }

    private fun flightFrom(text: String) =
        FlightExtractor.extract(text, LocalDate.of(2026, 8, 1)) { it in setOf("YUL", "FRA") }.single()

    @Test
    fun `PDF417 boarding pass barcode round trip`() {
        val (w, h, px) = pixels(PDF417Writer().encode(bcbp, BarcodeFormat.PDF_417, 0, 0), 3)
        val text = BarcodeDecoder.decode(w, h, px).single()
        assertEquals(bcbp, text)
        val f = flightFrom(text)
        assertEquals("YUL", f.from); assertEquals("FRA", f.to); assertEquals("834", f.flightNumber)
    }

    @Test
    fun `Aztec boarding pass barcode round trip`() {
        val (w, h, px) = pixels(AztecWriter().encode(bcbp, BarcodeFormat.AZTEC, 0, 0), 6)
        assertEquals(bcbp, BarcodeDecoder.decode(w, h, px).single())
    }

    @Test
    fun `light-on-dark barcode is also read`() {
        val (w, h, px) = pixels(PDF417Writer().encode(bcbp, BarcodeFormat.PDF_417, 0, 0), 3, invert = true)
        assertEquals(bcbp, BarcodeDecoder.decode(w, h, px).single())
    }

    @Test
    fun `an image without a barcode gives nothing`() {
        assertEquals(emptyList<String>(), BarcodeDecoder.decode(200, 200, IntArray(200 * 200) { 0xFFFFFFFF.toInt() }))
    }
}
