package dev.mriya.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mriya.data.Flight
import dev.mriya.domain.AirlineRankBy
import dev.mriya.domain.AirlineStat
import dev.mriya.domain.computeTopAirlines
import kotlin.math.roundToInt

private val AirlineBarViolet = Color(0xFF8B63F6)

/**
 * Horizontal bar chart of top airlines flown, switchable between flight count and total distance.
 */
@Composable
fun TopAirlinesCard(flights: List<Flight>, modifier: Modifier = Modifier) {
    var rankBy by rememberSaveable { mutableStateOf(AirlineRankBy.DISTANCE) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summary = remember(flights, rankBy) { computeTopAirlines(flights, rankBy) }
    val visible = if (expanded) summary.ranked else summary.ranked.take(8)
    val maxVal = remember(visible, rankBy) {
        when (rankBy) {
            AirlineRankBy.DISTANCE -> visible.maxOfOrNull { it.distanceKm }?.coerceAtLeast(1.0) ?: 1.0
            AirlineRankBy.FLIGHTS -> visible.maxOfOrNull { it.flights.toDouble() }?.coerceAtLeast(1.0) ?: 1.0
        }
    }

    MriyaCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Top Airlines",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Flight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp).rotate(45f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${summary.totalAirlines}",
                    fontFamily = Mono,
                    fontWeight = FontWeight.Black,
                    fontSize = 40.sp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (summary.totalAirlines == 1) "total airline" else "total airlines",
                    fontSize = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }

            if (summary.totalAirlines > 0) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RankModePill(
                        label = "Flights",
                        selected = rankBy == AirlineRankBy.FLIGHTS,
                        onClick = { rankBy = AirlineRankBy.FLIGHTS },
                    )
                    RankModePill(
                        label = "Distance",
                        selected = rankBy == AirlineRankBy.DISTANCE,
                        onClick = { rankBy = AirlineRankBy.DISTANCE },
                    )
                }

                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    visible.forEach { stat ->
                        val ratio = when (rankBy) {
                            AirlineRankBy.DISTANCE -> (stat.distanceKm / maxVal).toFloat()
                            AirlineRankBy.FLIGHTS -> (stat.flights.toDouble() / maxVal).toFloat()
                        }.coerceIn(0.08f, 1f)
                        AirlineBarRow(stat = stat, rankBy = rankBy, targetFraction = ratio)
                    }
                }

                if (summary.ranked.size > 8) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(if (expanded) "Show top 8" else "Show all ${summary.ranked.size}")
                    }
                }

                if (summary.untaggedFlights > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${summary.untaggedFlights} GPS-detected ${if (summary.untaggedFlights == 1) "flight has" else "flights have"} no airline tagged yet.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Airlines appear when flights are imported or matched from bookings, boarding passes, calendars, or manual entry.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RankModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun AirlineBarRow(stat: AirlineStat, rankBy: AirlineRankBy, targetFraction: Float) {
    val fraction by animateFloatAsState(targetValue = targetFraction, label = "airline_bar")
    val valueText = when (rankBy) {
        AirlineRankBy.DISTANCE -> "${"%,d".format(stat.distanceKm.roundToInt())} km"
        AirlineRankBy.FLIGHTS -> "${stat.flights} ${if (stat.flights == 1) "flight" else "flights"}"
    }
    val rowDescription = "${stat.name}: ${stat.flights} ${if (stat.flights == 1) "flight" else "flights"}, ${"%,d".format(stat.distanceKm.roundToInt())} km"

    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = rowDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AirlineBadge(stat.code, size = 34.dp, fallback = Color(stat.accentHex))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (stat.name != stat.code) {
                Text(
                    stat.name,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val maxBarWidth = (maxWidth - 96.dp).coerceAtLeast(48.dp)
                val barWidth = (maxBarWidth * fraction).coerceAtLeast(14.dp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(barWidth)
                            .height(24.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AirlineBarViolet)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        valueText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
