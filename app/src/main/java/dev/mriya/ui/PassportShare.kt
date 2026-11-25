package dev.mriya.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.mriya.data.CountryStamp
import dev.mriya.data.Flight
import dev.mriya.data.HolderProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The passport page with a one-tap Share button that renders exactly what is on screen to a PNG. */
@Composable
fun SharablePassportCard(
    year: Int?,
    stamps: List<CountryStamp>,
    flights: List<Flight>,
    modifier: Modifier = Modifier,
    tiltProvider: (() -> Offset)? = null,
    onTap: (() -> Unit)? = null,
) {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Box(modifier) {
        Box(
            Modifier
                .let { if (onTap != null) it.clickable(onClick = onTap) else it }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
        ) { PassportCard(year, stamps, flights, tiltProvider = tiltProvider) }
        Surface(
            onClick = {
                scope.launch {
                    val bmp = layer.toImageBitmap().asAndroidBitmap()
                    val uri = withContext(Dispatchers.IO) { savePng(ctx, bmp, "mriya-passport-${year ?: "all-time"}.png") }
                    sharePng(ctx, uri, "Share passport")
                }
            },
            shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(40.dp),
        ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Share, "Share passport", Modifier.size(20.dp)) } }
    }
}

/** The passport identity page with a one-tap Share button and tap-to-expand support. */
@Composable
fun SharableIdentityCard(
    year: Int?,
    stamps: List<CountryStamp>,
    flights: List<Flight>,
    profile: HolderProfile?,
    photoBytes: ByteArray?,
    tiltProvider: () -> Offset,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Box(modifier) {
        Box(
            Modifier
                .let { if (onTap != null) it.clickable(onClick = onTap) else it }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
        ) { PassportIdentityCard(year, stamps, flights, profile, photoBytes, tiltProvider) }
        Surface(
            onClick = {
                scope.launch {
                    val bmp = layer.toImageBitmap().asAndroidBitmap()
                    val uri = withContext(Dispatchers.IO) { savePng(ctx, bmp, "mriya-identity.png") }
                    sharePng(ctx, uri, "Share identity page")
                }
            },
            shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(40.dp),
        ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Share, "Share identity page", Modifier.size(20.dp)) } }
    }
}

private fun sharePng(ctx: Context, uri: android.net.Uri, title: String) {
    ctx.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            title,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** Writes to the app cache (not external storage) and exposes it through FileProvider for the share sheet only. */
private fun savePng(ctx: Context, bmp: Bitmap, filename: String): android.net.Uri {
    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() } // keep only the latest share
    val f = File(dir, filename)
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
}
