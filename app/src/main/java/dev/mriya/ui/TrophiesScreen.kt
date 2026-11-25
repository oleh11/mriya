package dev.mriya.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.mriya.domain.Achievement
import dev.mriya.domain.Rank
import dev.mriya.domain.Tier
import dev.mriya.domain.Trophies
import dev.mriya.domain.TravelStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private fun tierColors(t: Tier?) = when (t) {
    Tier.BRONZE -> listOf(Color(0xFFE8B98A), Color(0xFFB4713D))
    Tier.SILVER -> listOf(Color(0xFFF2F4F7), Color(0xFF9AA5B4))
    Tier.GOLD -> listOf(Color(0xFFFFE08A), Color(0xFFD9A21B))
    null -> listOf(Color(0xFFB9A8FF), Color(0xFF5B3FD0))
}

/** Seven progressively ornate Ukrainian-inspired rank seals (culminating in the 6-engine An-225 Mriya seal). */
@Composable
fun RankSeal(rank: Rank, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val idx = Trophies.ranks.indexOfFirst { it.title == rank.title }.coerceAtLeast(0)
    val bg = when (idx) {
        0 -> listOf(Color(0xFFD6E4FF), Color(0xFF7E8CE0))
        1 -> listOf(Color(0xFFC5EFE6), Color(0xFF2D8C85))
        2 -> listOf(Color(0xFFD3CBFF), Color(0xFF5B3FD0))
        3 -> listOf(Color(0xFFFFE4C7), Color(0xFF8C4A9E))
        4 -> listOf(Color(0xFFEAF0F8), Color(0xFF3E4A89))
        5 -> listOf(Color(0xFFFFE08A), Color(0xFF4A2CB5))
        else -> listOf(Color(0xFFFFE58F), Color(0xFFD9A21B), Color(0xFF2A1D6E))
    }
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val c = center
        drawCircle(Brush.linearGradient(bg, Offset.Zero, Offset(this.size.width, this.size.height)), r)
        drawCircle(Color.White.copy(alpha = 0.65f), r * 0.90f, style = Stroke(r * 0.045f))

        if (idx >= 1) {
            drawCircle(Color.White.copy(alpha = 0.45f), r * 0.77f, style = Stroke(r * 0.03f))
        }
        if (idx >= 2) {
            val ticks = 8 + idx * 4
            for (k in 0 until ticks) {
                rotate(k * (360f / ticks), c) {
                    drawLine(
                        Color.White.copy(alpha = if (idx >= 5) 0.7f else 0.45f),
                        Offset(c.x, c.y - r * 0.87f),
                        Offset(c.x, c.y - r * 0.79f),
                        strokeWidth = r * 0.03f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        if (idx >= 3) {
            // Subtle vyshyvanka diamond ring at N/E/S/W/diagonals.
            val count = if (idx >= 5) 8 else 4
            for (k in 0 until count) {
                rotate(k * (360f / count), c) {
                    val d = r * 0.07f
                    val dy = c.y - r * 0.66f
                    val diamond = Path().apply {
                        moveTo(c.x, dy - d); lineTo(c.x + d, dy); lineTo(c.x, dy + d); lineTo(c.x - d, dy); close()
                    }
                    drawPath(diamond, Color(0xFFC8102E).copy(alpha = 0.85f))
                }
            }
        }

        if (idx == 6) {
            // Top rank: stylized 6-engine Antonov An-225 Mriya silhouette with swept wings and twin H-tail.
            drawCircle(Color(0xFF1C1B4B), r * 0.58f)
            drawCircle(Color(0xFFFFD166), r * 0.58f, style = Stroke(r * 0.035f))
            rotate(-30f, c) {
                val u = r * 0.052f
                val plane = Path().apply {
                    moveTo(c.x + 7.5f * u, c.y)
                    lineTo(c.x + 4.5f * u, c.y - 1.0f * u)
                    lineTo(c.x + 2.2f * u, c.y - 1.0f * u)
                    lineTo(c.x - 2.4f * u, c.y - 7.6f * u)
                    lineTo(c.x - 3.6f * u, c.y - 7.2f * u)
                    lineTo(c.x - 1.0f * u, c.y - 1.0f * u)
                    lineTo(c.x - 5.0f * u, c.y - 0.8f * u)
                    lineTo(c.x - 6.6f * u, c.y - 3.6f * u)
                    lineTo(c.x - 7.6f * u, c.y - 3.4f * u)
                    lineTo(c.x - 6.6f * u, c.y - 0.5f * u)
                    lineTo(c.x - 7.2f * u, c.y)
                    lineTo(c.x - 6.6f * u, c.y + 0.5f * u)
                    lineTo(c.x - 7.6f * u, c.y + 3.4f * u)
                    lineTo(c.x - 6.6f * u, c.y + 3.6f * u)
                    lineTo(c.x - 5.0f * u, c.y + 0.8f * u)
                    lineTo(c.x - 1.0f * u, c.y + 1.0f * u)
                    lineTo(c.x - 3.6f * u, c.y + 7.2f * u)
                    lineTo(c.x - 2.4f * u, c.y + 7.6f * u)
                    lineTo(c.x + 2.2f * u, c.y + 1.0f * u)
                    lineTo(c.x + 4.5f * u, c.y + 1.0f * u)
                    close()
                }
                drawPath(plane, Color.White)
                // 6 engine pods + twin vertical tailfin endplates.
                listOf(-2.4f, -4.2f, -6.0f, 2.4f, 4.2f, 6.0f).forEachIndexed { i, ey ->
                    val ex = when (i % 3) { 0 -> 1.4f; 1 -> 0.2f; else -> -1.0f }
                    drawCircle(Color(0xFFFFD166), 0.7f * u, Offset(c.x + ex * u, c.y + ey * u))
                }
                drawLine(Color.White, Offset(c.x - 5.2f * u, c.y - 3.6f * u), Offset(c.x - 8.1f * u, c.y - 3.6f * u), strokeWidth = 0.85f * u)
                drawLine(Color.White, Offset(c.x - 5.2f * u, c.y + 3.6f * u), Offset(c.x - 8.1f * u, c.y + 3.6f * u), strokeWidth = 0.85f * u)
            }
        } else {
            val s = r * (0.48f + idx * 0.03f)
            val star = if (idx >= 5) Color(0xFFD9A21B) else Color(0xFFC8102E)
            rotate(0f, c) { drawRect(star, Offset(c.x - s / 2, c.y - s / 2), Size(s, s)) }
            rotate(45f, c) { drawRect(star, Offset(c.x - s / 2, c.y - s / 2), Size(s, s)) }
            if (idx >= 4) {
                val inner = s * 0.56f
                rotate(22.5f, c) { drawRect(Color.White.copy(alpha = 0.8f), Offset(c.x - inner / 2, c.y - inner / 2), Size(inner, inner)) }
            }
            drawCircle(Color(0xFF1B1B1B), r * 0.12f, c)
        }
    }
}

/** A round medal with a vyshyvanka star in the middle. [t] null = the rank medal. */
@Composable
fun Medal(t: Tier?, size: androidx.compose.ui.unit.Dp, locked: Boolean = false, modifier: Modifier = Modifier) {
    val colors = if (locked) listOf(Color(0xFFD5DAE0), Color(0xFF9AA3AE)) else tierColors(t)
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(Brush.linearGradient(colors, Offset.Zero, Offset(this.size.width, this.size.height)), r)
        drawCircle(Color.White.copy(alpha = 0.55f), r * 0.86f, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.05f))
        val s = r * 0.62f
        val c = center
        val star = if (locked) Color(0xFF7A838F) else Color(0xFFC8102E)
        rotate(0f, c) { drawRect(star, Offset(c.x - s / 2, c.y - s / 2), Size(s, s)) }
        rotate(45f, c) { drawRect(star, Offset(c.x - s / 2, c.y - s / 2), Size(s, s)) }
        drawCircle(if (locked) Color(0xFFB7BEC7) else Color(0xFF1B1B1B), r * 0.14f, c)
    }
}

@Composable
fun TrophiesScreen(ui: UiState) {
    val stats = remember(ui.data, ui.continents) { Trophies.stats(ui.data) { ui.continents[it] } }
    val all = remember(stats) { Trophies.achievements(stats) }
    val xp = Trophies.xp(stats)
    val rank = Trophies.rank(xp)
    val next = Trophies.nextRank(xp)
    var sheet by remember { mutableStateOf<TrophySheet?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { RankCard(rank, next, xp, all.count { it.unlocked }, all.size) { sheet = TrophySheet.ForRank } }
        val nextUp = all.filter { !it.unlocked }.sortedByDescending { it.fraction }.take(3)
        if (nextUp.isNotEmpty()) {
            item { SectionLabel("Next up") }
            items(nextUp, key = { "up_" + it.id }) { AchievementRow(it) {} }
        }
        all.groupBy { it.group }.forEach { (group, list) ->
            item { SectionLabel(group, "${list.count { it.unlocked }}/${list.size}") }
            items(list, key = { it.id }) { a -> AchievementRow(a) { if (a.unlocked) sheet = TrophySheet.ForAchievement(a) } }
        }
    }
    sheet?.let { s -> TrophyShareSheet(s, stats, rank, xp) { sheet = null } }
}

private sealed interface TrophySheet {
    object ForRank : TrophySheet
    data class ForAchievement(val a: Achievement) : TrophySheet
}

@Composable
private fun RankCard(rank: Rank, next: Rank?, xp: Int, unlocked: Int, total: Int, onShare: () -> Unit) {
    MriyaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RankSeal(rank, 68.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("RANK", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.5.sp)
                    Text(rank.title, fontWeight = FontWeight.Black, fontSize = 30.sp)
                }
                RoundIcon(onShare) { Icon(Icons.Default.Share, "Share my rank", Modifier.size(20.dp)) }
            }
            Spacer(Modifier.height(16.dp))
            val target = next?.minXp ?: xp
            val start = rank.minXp
            val frac = if (next == null) 1f else ((xp - start).toFloat() / (target - start)).coerceIn(0f, 1f)
            val animated by animateFloatAsState(frac, label = "xp")
            LinearProgressIndicator(progress = { animated }, Modifier.fillMaxWidth().height(10.dp).clip(CircleShape))
            Spacer(Modifier.height(8.dp))
            Text(
                if (next == null) "${"%,d".format(xp)} XP: top rank reached" else "${"%,d".format(xp)} XP  •  ${"%,d".format(next.minXp - xp)} to ${next.title}",
                fontFamily = Mono, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Chip("$unlocked of $total trophies")
        }
    }
}

@Composable
private fun AchievementRow(a: Achievement, onClick: () -> Unit) {
    MriyaCard(Modifier.fillMaxWidth().clickable(enabled = a.unlocked, onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Medal(a.tier, 48.dp, locked = !a.unlocked)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(a.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!a.unlocked && a.target > 1) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { a.fraction }, Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                    Text("${"%,d".format(a.progress)} / ${"%,d".format(a.target)}", fontFamily = Mono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (a.unlocked) Icon(Icons.Default.CheckCircle, "Unlocked", tint = MriyaColors.Ok)
        }
    }
}

// ---- the shareable trophy post -------------------------------------------------------------------------

/**
 * The picture that gets shared. It shows the trophy, rank and overall totals only: no places, dates or routes,
 * so sharing it reveals nothing about where you have been.
 */
@Composable
fun TrophyCard(title: String, subtitle: String, tier: Tier?, rank: Rank, xp: Int, footer: String, modifier: Modifier = Modifier) {
    val bg = when (tier) {
        Tier.BRONZE -> listOf(Color(0xFFFFE9D6), Color(0xFFF3C9A3))
        Tier.SILVER -> listOf(Color(0xFFF4F6FA), Color(0xFFD3DAE6))
        Tier.GOLD -> listOf(Color(0xFFFFF3C4), Color(0xFFF6D56B))
        null -> listOf(Color(0xFFD3CBFF), Color(0xFFB9D7FF))
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.verticalGradient(bg)).padding(bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmbroideryBand(Modifier.padding(top = 16.dp))
        Spacer(Modifier.height(22.dp))
        if (tier == null) RankSeal(rank, 132.dp) else Medal(tier, 132.dp)
        Spacer(Modifier.height(18.dp))
        Text(title, color = Color(0xFF1C1B4B), fontWeight = FontWeight.Black, fontSize = 26.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = Color(0xFF1C1B4B).copy(alpha = 0.75f), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(18.dp))
        Text("RANK  ${rank.title.uppercase()}  •  ${"%,d".format(xp)} XP", color = Color(0xFF1C1B4B), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(footer, color = Color(0xFF1C1B4B).copy(alpha = 0.7f), fontFamily = Mono, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Text("MRIYA", color = Color(0xFF1C1B4B).copy(alpha = 0.55f), fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 4.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrophyShareSheet(sheet: TrophySheet, stats: TravelStats, rank: Rank, xp: Int, onClose: () -> Unit) {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val footer = "${stats.countries} countries  •  ${stats.flights} flights  •  ${"%,d".format(stats.totalKm.toInt())} km"
    val (title, subtitle, tier, shareText) = when (sheet) {
        is TrophySheet.ForAchievement -> Quad(sheet.a.title, sheet.a.description, sheet.a.tier, "Trophy unlocked in Mriya: ${sheet.a.title}")
        TrophySheet.ForRank -> Quad("Rank: ${rank.title}", "${"%,d".format(xp)} XP of travel", null, "My Mriya rank: ${rank.title}")
    }
    // Open at full height and scroll, so the Share button is always reachable.
    ModalBottomSheet(onDismissRequest = onClose, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }) {
                TrophyCard(title, subtitle, tier, rank, xp, footer)
            }
            Spacer(Modifier.height(16.dp))
            Text("The picture shows only the trophy, your rank and totals. No places or dates.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        val bmp = layer.toImageBitmap().asAndroidBitmap()
                        val uri = withContext(Dispatchers.IO) { saveTrophyPng(ctx, bmp, title) }
                        ctx.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                "Share trophy",
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("Share trophy", fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

private data class Quad(val a: String, val b: String, val c: Tier?, val d: String)

/** App cache only, shared through FileProvider for the share sheet; only the latest picture is kept. */
private fun saveTrophyPng(ctx: Context, bmp: Bitmap, title: String): android.net.Uri {
    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val name = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "trophy" }
    val f = File(dir, "mriya-$name.png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
}
