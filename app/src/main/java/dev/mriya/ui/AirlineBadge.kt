package dev.mriya.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * A round badge for an airline: its IATA code on a brand-coloured disc. Drawn locally, so nothing is downloaded and
 * no trademarked artwork ships in the app. Airlines without a known colour get a neutral disc.
 */
object AirlineStyle {
    // background, text colour (0xRRGGBB). Approximate brand colours of the most flown airlines.
    private val brand: Map<String, Pair<Int, Int>> = mapOf(
        "UA" to (0x0033A0 to 0xFFFFFF), "AA" to (0xB61F23 to 0xFFFFFF), "DL" to (0x003366 to 0xFFFFFF),
        "WN" to (0x304CB2 to 0xFFC20E), "B6" to (0x0033A0 to 0xFFFFFF), "AS" to (0x01426A to 0xFFFFFF),
        "NK" to (0xFFEC00 to 0x000000), "F9" to (0x00674F to 0xFFFFFF), "HA" to (0x5B1E86 to 0xFFFFFF),
        "AC" to (0xF01428 to 0xFFFFFF), "WS" to (0x00A0DF to 0xFFFFFF), "AM" to (0x0B2161 to 0xFFFFFF),
        "Y4" to (0x6A1B9A to 0xFFFFFF), "VB" to (0x00A859 to 0xFFFFFF), "LA" to (0x1B0088 to 0xFFFFFF),
        "AV" to (0xDA291C to 0xFFFFFF), "CM" to (0x0072CE to 0xFFFFFF), "G3" to (0xFF7020 to 0xFFFFFF),
        "BA" to (0x075AAA to 0xFFFFFF), "VS" to (0xE10A0A to 0xFFFFFF), "LH" to (0x05164D to 0xFFCB00),
        "AF" to (0x002157 to 0xFFFFFF), "KL" to (0x00A1DE to 0xFFFFFF), "IB" to (0xD7192D to 0xFFFFFF),
        "LX" to (0xE2001A to 0xFFFFFF), "OS" to (0xE2001A to 0xFFFFFF), "SN" to (0x00205B to 0xFFFFFF),
        "SK" to (0x0B2D6B to 0xFFFFFF), "AY" to (0x0B1560 to 0xFFFFFF), "TP" to (0x00A859 to 0xFFFFFF),
        "AZ" to (0x006643 to 0xFFFFFF), "EI" to (0x00A28A to 0xFFFFFF), "FR" to (0x073590 to 0xF1C933),
        "U2" to (0xFF6600 to 0xFFFFFF), "W6" to (0xC6007E to 0xFFFFFF), "VY" to (0xFFCC00 to 0x333333),
        "PS" to (0x0057B8 to 0xFFFFFF), "LO" to (0x11397E to 0xFFFFFF), "OK" to (0xD7141A to 0xFFFFFF),
        "TK" to (0xC70A0C to 0xFFFFFF), "EK" to (0xD71921 to 0xFFFFFF), "QR" to (0x5C0632 to 0xFFFFFF),
        "EY" to (0xBD8B13 to 0xFFFFFF), "SV" to (0x00A859 to 0xFFFFFF), "MS" to (0x1C3F94 to 0xFFFFFF),
        "ET" to (0x078930 to 0xFCDD09), "KQ" to (0xD71920 to 0xFFFFFF), "SA" to (0x0099CC to 0xFFFFFF),
        "SQ" to (0x1B2A5B to 0xF9A826), "CX" to (0x005D63 to 0xFFFFFF), "QF" to (0xE0001A to 0xFFFFFF),
        "NZ" to (0x000000 to 0xFFFFFF), "JL" to (0xC8102E to 0xFFFFFF), "NH" to (0x13448F to 0xFFFFFF),
        "KE" to (0x00256C to 0xFFFFFF), "OZ" to (0xA0A0A0 to 0xFFFFFF), "CI" to (0xB7003C to 0xFFFFFF),
        "BR" to (0x00693C to 0xFFFFFF), "CA" to (0xE60012 to 0xFFFFFF), "MU" to (0x0033A0 to 0xFFFFFF),
        "CZ" to (0x0066B3 to 0xFFFFFF), "AI" to (0xE31837 to 0xFFFFFF), "6E" to (0x2E3192 to 0xFFFFFF),
        "TG" to (0x4D2A86 to 0xFFFFFF), "MH" to (0xC8102E to 0xFFFFFF), "GA" to (0x00A3E0 to 0xFFFFFF),
        "PR" to (0x0B3D91 to 0xFFFFFF), "VN" to (0x00457C to 0xFFD100), "AK" to (0xFF0000 to 0xFFFFFF),
        "PK" to (0x00693C to 0xFFFFFF), "LY" to (0x1A3C8C to 0xFFFFFF), "RJ" to (0xB59A57 to 0x000000),
        "UK" to (0x4B2E83 to 0xFFFFFF), "PC" to (0xFFCC00 to 0x1A1A1A), "SU" to (0x0A2E6E to 0xFFFFFF),
        "PD" to (0x0B2161 to 0xFFFFFF), "AR" to (0x3B8EDE to 0xFFFFFF), "G4" to (0x2F7CBF to 0xFFFFFF),
        "SY" to (0xF26A21 to 0xFFFFFF), "QX" to (0x00205B to 0xFFFFFF), "OO" to (0x003E7E to 0xFFFFFF),
        "MQ" to (0xB61F23 to 0xFFFFFF), "YX" to (0x0B2D6B to 0xFFFFFF), "9E" to (0x003366 to 0xFFFFFF),
    )

    private val neutral = 0x3A4150 to 0xFFFFFF

    fun colors(code: String?): Pair<Color, Color> {
        val (bg, fg) = brand[code?.trim()?.uppercase(Locale.ROOT)] ?: neutral
        return Color(0xFF000000.toInt() or bg) to Color(0xFF000000.toInt() or fg)
    }

    fun isKnown(code: String?) = code?.trim()?.uppercase(Locale.ROOT) in brand
}

/** The disc itself. Shows nothing when no airline code is known. */
@Composable
fun AirlineBadge(code: String?, modifier: Modifier = Modifier, size: Dp = 40.dp, fallback: Color? = null) {
    val c = code?.trim()?.uppercase(Locale.ROOT).orEmpty()
    if (c.isEmpty()) return
    val (bg, fg) = if (fallback != null && !AirlineStyle.isKnown(c)) fallback to Color.White else AirlineStyle.colors(c)
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(c.take(3), color = fg, fontWeight = FontWeight.Black, fontSize = (size.value * 0.36f).sp, maxLines = 1)
    }
}
