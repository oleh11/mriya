package dev.mriya.domain.imports

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader

/**
 * Reads the barcodes on a rendered boarding pass (PDF417, Aztec, QR, Data Matrix) with ZXing: open source, pure Java,
 * no network and no telemetry. Works on raw pixels so it can be unit-tested without Android.
 */
object BarcodeDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.PDF_417, BarcodeFormat.AZTEC, BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX),
        DecodeHintType.TRY_HARDER to true,
    )

    /** [pixels] are ARGB ints, row by row. Tries a few binarisation strategies and returns every distinct code found. */
    fun decode(width: Int, height: Int, pixels: IntArray): List<String> {
        val source = RGBLuminanceSource(width, height, pixels)
        val attempts = listOf(
            { BinaryBitmap(HybridBinarizer(source)) },
            { BinaryBitmap(GlobalHistogramBinarizer(source)) },
            { BinaryBitmap(HybridBinarizer(source.invert())) }, // light-on-dark codes
        )
        val found = LinkedHashSet<String>()
        for (make in attempts) {
            runCatching { GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(make(), hints) }
                .getOrNull()?.forEach { found += it.text }
            if (found.isNotEmpty()) break
        }
        return found.toList()
    }
}
