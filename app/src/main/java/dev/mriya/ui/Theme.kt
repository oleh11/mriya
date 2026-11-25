package dev.mriya.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design language shared with ObminFX: pastel gradient, big white cards, black pills, mono numerals. */
object MriyaColors {
    val Accent = Color(0xFF6B4EE6)
    val Ok = Color(0xFF16B364)
    val LightTop = Color(0xFFD6EFE6)
    val LightBottom = Color(0xFFE9E4F3)
    val DarkTop = Color(0xFF0F1714)
    val DarkBottom = Color(0xFF14121C)
}

val Mono = FontFamily.Monospace

@Composable
fun MriyaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Color.White, onPrimary = Color.Black,
        background = MriyaColors.DarkTop, onBackground = Color(0xFFF2F4F3),
        surface = Color(0xFF1B2026), onSurface = Color(0xFFF2F4F3),
        surfaceVariant = Color(0xFF272D35), onSurfaceVariant = Color(0xFFA9B2BC),
        outline = Color(0xFF39414B),
    ) else lightColorScheme(
        primary = Color.Black, onPrimary = Color.White,
        background = MriyaColors.LightTop, onBackground = Color(0xFF0B0F0E),
        surface = Color.White, onSurface = Color(0xFF0B0F0E),
        surfaceVariant = Color(0xFFEDF0F3), onSurfaceVariant = Color(0xFF5B6670),
        outline = Color(0xFFDDE2E7),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun GradientBackground(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                if (dark) listOf(MriyaColors.DarkTop, MriyaColors.DarkBottom)
                else listOf(MriyaColors.LightTop, MriyaColors.LightBottom)
            )
        )
    ) { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground) { content() } }
}

/** Large rounded white card. */
@Composable
fun MriyaCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground) { content() } }
}

/** Black pill badge, like "Add Currency" / "LIVE FX". */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Text(
            text,
            Modifier.padding(horizontal = if (small) 8.dp else 14.dp, vertical = if (small) 2.dp else 6.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = if (small) 10.sp else 14.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

/** Light grey chip. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, selected: Boolean = false) {
    Surface(
        modifier, shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text, Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
    }
}
