package dev.mriya.data

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Offline on-device OCR for flight screenshots (Google Wallet, airline apps) using Apache-2.0
 * Tesseract 5 (`tesseract4android`) with the bundled English `tessdata_fast` model. Zero network
 * calls and zero Play Services dependencies.
 */
class ScreenshotOcr(context: Context) {
    private val ctx = context.applicationContext
    private val dataPath: String by lazy { ensureTrainedData() }

    private fun ensureTrainedData(): String {
        val dir = File(ctx.filesDir, "tessdata").apply { mkdirs() }
        val target = File(dir, "eng.traineddata")
        if (!target.exists() || target.length() == 0L) {
            val tmp = File(dir, "eng.traineddata.tmp")
            ctx.assets.open("tessdata/eng.traineddata").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        return ctx.filesDir.absolutePath
    }

    /**
     * Yields OCR text lazily across three targeted passes (upscaling low-res screenshots to at
     * least 1200px wide so small card text reaches optimal cap height for Tesseract LSTM):
     * 1. Upscaled full image (`PSM_SPARSE_TEXT`) for UI cards.
     * 2. Top 65% crop, contrast-stretched non-inverted grayscale (`PSM_AUTO`) for light-theme cards.
     * 3. Top 65% crop, contrast-stretched + inverted grayscale (`PSM_SPARSE_TEXT`) for colored/dark cards.
     */
    fun recognizeVariants(bitmap: Bitmap): Sequence<String> = sequence {
        val path = dataPath
        val baseCopy = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else null
        val source = baseCopy ?: bitmap
        var scaled: Bitmap? = null
        try {
            val working = if (source.width in 1 until 1200) {
                val scale = (1200f / source.width).coerceAtMost(3f)
                val sw = (source.width * scale).toInt().coerceAtLeast(1)
                val sh = (source.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(source, sw, sh, true).also { scaled = it }
            } else {
                source
            }

            val pass1 = runTess(path, working, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            if (pass1.isNotBlank()) yield(pass1)

            val cropH = if (working.height > (working.width * 1.4f).toInt()) {
                (working.height * 0.65f).toInt().coerceAtLeast(1)
            } else {
                working.height
            }

            val stretched = preprocessGray(working, cropH, invert = false)
            try {
                val pass2 = runTess(path, stretched, TessBaseAPI.PageSegMode.PSM_AUTO)
                if (pass2.isNotBlank()) yield(pass2)
            } finally {
                stretched.recycle()
            }

            val inverted = preprocessGray(working, cropH, invert = true)
            try {
                val pass3 = runTess(path, inverted, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
                if (pass3.isNotBlank()) yield(pass3)
            } finally {
                inverted.recycle()
            }
        } finally {
            scaled?.recycle()
            baseCopy?.recycle()
        }
    }

    private fun runTess(path: String, bmp: Bitmap, psm: Int): String {
        val tess = TessBaseAPI()
        return try {
            if (!tess.init(path, "eng")) return ""
            tess.setPageSegMode(psm)
            tess.setImage(bmp)
            tess.utF8Text.orEmpty()
        } finally {
            tess.recycle()
        }
    }

    private fun preprocessGray(src: Bitmap, cropHeight: Int, invert: Boolean): Bitmap {
        val w = src.width.coerceAtLeast(1)
        val h = cropHeight.coerceIn(1, src.height)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        var minLum = 255
        var maxLum = 0
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val lum = (299 * r + 587 * g + 114 * b) / 1000
            pixels[i] = lum
            if (lum < minLum) minLum = lum
            if (lum > maxLum) maxLum = lum
        }

        val span = maxLum - minLum
        for (i in pixels.indices) {
            var v = if (span > 16) ((pixels[i] - minLum) * 255) / span else pixels[i]
            if (invert) v = 255 - v
            v = v.coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}
