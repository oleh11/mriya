package dev.mriya.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mriya.data.CountryStamp
import dev.mriya.data.Flight
import dev.mriya.data.HolderProfile
import dev.mriya.domain.ProfileUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val IdInk = Color(0xFF1C1B4B)

private fun idFlag(iso: String?): String =
    if (iso == null || iso.length != 2) "🌐"
    else iso.uppercase(Locale.ROOT).map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

/**
 * Biometric polycarbonate-style passport page with the travel-record world map, flags, and flight
 * totals on top and the user's encrypted biometric identity details and MRZ on the bottom.
 * Uses fictional issuer code `MRY` and explicitly states "NOT AN OFFICIAL TRAVEL DOCUMENT".
 */
@Composable
fun PassportIdentityCard(
    year: Int?,
    stamps: List<CountryStamp>,
    flights: List<Flight>,
    profile: HolderProfile?,
    photoBytes: ByteArray?,
    tiltProvider: () -> Offset,
    modifier: Modifier = Modifier,
) {
    val p = profile ?: HolderProfile()
    val portrait = remember(photoBytes) {
        photoBytes?.let { b ->
            runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull()
        }
    }
    val (mrz1, mrz2) = remember(p) { ProfileUtils.buildIdentityMrz(p) }

    val zone = ZoneId.systemDefault()
    val yFlights = if (year == null) flights else flights.filter { Instant.ofEpochSecond(it.departTs).atZone(zone).year == year }
    val yStamps = if (year == null) stamps else stamps.filter { s -> s.days.any { it.startsWith("$year-") } }
    val km = yFlights.sumOf { it.distanceKm }
    val seconds = yFlights.sumOf { it.arriveTs - it.departTs }
    val airports = yFlights.flatMap { listOfNotNull(it.fromAirport, it.toAirport) }.toSet()
    val origin = yFlights.mapNotNull { it.fromAirport }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "—"
    val since = stamps.minOfOrNull { it.firstTs }?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
    val fmt = DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH)
    val issued = LocalDate.now().format(fmt).uppercase(Locale.ROOT)
    val hh = seconds / 3600
    val mm = (seconds % 3600) / 60

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFCDE9DF), Color(0xFFB7D6FF), Color(0xFFD2CAFF), Color(0xFFEACAF2))
                )
            ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            EmbroideryBand(Modifier.padding(top = 12.dp))
            Text(
                "PASSPORT  •  ПАСПОРТ  •  NOT AN OFFICIAL TRAVEL DOCUMENT  •  MRY",
                color = IdInk.copy(alpha = 0.56f),
                fontFamily = Mono,
                fontSize = 7.5.sp,
                letterSpacing = 0.9.sp,
                maxLines = 1,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 2.dp),
            )

            // Top half: World DotMap, visited country flags, and travel record summary
            DotMap(
                visitedIso = yStamps.mapNotNull { it.iso2 }.toSet(),
                flights = yFlights,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
            if (yStamps.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    yStamps.sortedBy { it.firstTs }.take(12).forEach {
                        Text(idFlag(it.iso2), fontSize = 15.sp, modifier = Modifier.padding(horizontal = 2.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${year ?: "ALL-TIME"} TRAVEL ENDORSEMENT",
                        color = IdInk.copy(alpha = 0.72f),
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.5.sp,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${airports.size} airports",
                        color = IdInk.copy(alpha = 0.72f),
                        fontFamily = Mono,
                        fontSize = 9.5.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IdStat("Flights", "${yFlights.size}")
                    IdStat("Distance", "${"%,d".format(km.roundToInt())} km")
                    IdStat("Flight time", "${hh}h ${"%02d".format(mm)}m")
                    IdStat("Countries", "${yStamps.size}")
                }
            }

            HorizontalDivider(color = IdInk.copy(alpha = 0.16f), thickness = 1.dp)

            // Bottom half: Polycarbonate biometric identity panel
            Column(Modifier.background(Color.White.copy(alpha = 0.42f)).padding(horizontal = 18.dp, vertical = 12.dp).fillMaxWidth()) {
                // Header bar with Holographic Mriya icon + Type / Code / Passport No / Biometric symbol + Kinegram seal
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HolographicMriyaIcon(tiltProvider, Modifier.size(44.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "MRIYA PASSPORT",
                                color = IdInk,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.5.sp,
                                letterSpacing = 0.6.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            BiometricChipIcon(IdInk.copy(alpha = 0.72f), Modifier.size(width = 20.dp, height = 13.dp))
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "TYPE P  •  CODE MRY  •  NO. ${p.passportNumber.ifBlank { "MR0000000" }}",
                            color = IdInk.copy(alpha = 0.74f),
                            fontFamily = Mono,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    KinegramSeal(tiltProvider, Modifier.size(44.dp))
                }

                Spacer(Modifier.height(10.dp))

                // Biometric portrait + Personal & Issuing biodata
                Box(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .size(width = 82.dp, height = 102.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.72f))
                                .border(1.4.dp, IdInk.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (portrait != null) {
                                Image(
                                    bitmap = portrait,
                                    contentDescription = "Holder portrait",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(Icons.Default.Person, "No portrait", Modifier.size(38.dp), tint = IdInk.copy(alpha = 0.38f))
                            }
                            // Fine corner registration hairlines inside portrait frame.
                            Canvas(Modifier.fillMaxSize()) {
                                val arm = 8f * density
                                val c = IdInk.copy(alpha = 0.45f)
                                val sw = 1.2f * density
                                drawLine(c, Offset(4f, 4f), Offset(4f + arm, 4f), sw)
                                drawLine(c, Offset(4f, 4f), Offset(4f, 4f + arm), sw)
                                drawLine(c, Offset(size.width - 4f, size.height - 4f), Offset(size.width - 4f - arm, size.height - 4f), sw)
                                drawLine(c, Offset(size.width - 4f, size.height - 4f), Offset(size.width - 4f, size.height - 4f - arm), sw)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            IdField("SURNAME", p.surname.ifBlank { "—" }.uppercase(Locale.ROOT))
                            IdField("GIVEN NAMES", p.givenNames.ifBlank { "Add profile in Settings" }.uppercase(Locale.ROOT))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IdField("NATIONALITY", p.nationality.ifBlank { "MRY" }.uppercase(Locale.ROOT), Modifier.weight(1f))
                                IdField("DATE OF BIRTH", p.dateOfBirth.ifBlank { "—" }, Modifier.weight(1.2f))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IdField("PLACE OF BIRTH", p.placeOfBirth.ifBlank { "—" }.uppercase(Locale.ROOT), Modifier.weight(1f))
                                IdField("HOME AIRPORT", origin, Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IdField("ISSUED", issued, Modifier.weight(1f))
                                IdField("MEMBER SINCE", since?.format(fmt)?.uppercase(Locale.ROOT) ?: "—", Modifier.weight(1f))
                            }
                        }
                    }

                    // Polycarbonate ghost portrait security window.
                    if (portrait != null) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(34.dp)
                                .clip(CircleShape)
                                .border(0.8.dp, IdInk.copy(alpha = 0.28f), CircleShape),
                        ) {
                            Image(
                                bitmap = portrait,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().alpha(0.20f),
                            )
                        }
                    }
                }
            }

            PassportMrzStrip(mrz1, mrz2)
        }
        HolographicOverlay(tiltProvider, Modifier.matchParentSize(), detailedPassportMode = true)
    }
}

/**
 * Full-width ICAO 9303 44-column MRZ footer calibrated dynamically to span cleanly edge-to-edge.
 */
@Composable
fun PassportMrzStrip(line1: String, line2: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xEEFFFFFF)),
    ) {
        HorizontalDivider(color = IdInk.copy(alpha = 0.14f), thickness = 0.8.dp)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            val availWidthPx = constraints.maxWidth.toFloat()
            val refStr = "P<MRY<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"
            val refStyle = remember {
                TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp)
            }
            val refWidthPx = remember(measurer) {
                measurer.measure(refStr, refStyle).size.width.toFloat().coerceAtLeast(1f)
            }
            val fontSizeSp = (12f * (availWidthPx * 0.90f / refWidthPx)).coerceIn(7f, 14f).sp
            val measuredWidthPx = remember(measurer, fontSizeSp) {
                measurer.measure(
                    refStr,
                    TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = fontSizeSp),
                ).size.width.toFloat()
            }
            val gapPx = ((availWidthPx - measuredWidthPx) / 44f).coerceAtLeast(0f)
            val letterSpacingSp = with(density) { gapPx.toSp() }
            val mrzStyle = TextStyle(
                color = Color(0xFF15143B),
                fontFamily = Mono,
                fontWeight = FontWeight.Medium,
                fontSize = fontSizeSp,
                letterSpacing = letterSpacingSp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(line1, style = mrzStyle, maxLines = 1, softWrap = false)
                Text(line2, style = mrzStyle, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun IdField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = IdInk.copy(alpha = 0.62f), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
        Text(
            value,
            color = IdInk,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun IdStat(label: String, value: String) {
    Column {
        Text(label, color = IdInk.copy(alpha = 0.72f), fontSize = 10.sp)
        Text(value, color = IdInk, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
