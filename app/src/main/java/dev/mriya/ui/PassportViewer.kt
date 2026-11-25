package dev.mriya.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.mriya.data.CountryStamp
import dev.mriya.data.Flight
import dev.mriya.data.HolderProfile
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Interactive passport booklet with a navy/gold hardcover that swings open in 3D from the left
 * spine to reveal a swipeable 2-page holographic deck (Travel Record + Identity Page).
 * Tapping an opened page launches the full-screen interactive zoom/pan viewer.
 */
@Composable
fun PassportDeck(
    year: Int?,
    stamps: List<CountryStamp>,
    flights: List<Flight>,
    profile: HolderProfile?,
    photoBytes: ByteArray?,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val reduceMotion = remember { isReduceMotionEnabled(ctx) }
    val tiltState = rememberTiltOffset(enabled = !reduceMotion)
    val tiltProvider = remember(tiltState) { { tiltState.value } }
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    var opened by rememberSaveable { mutableStateOf(false) }
    var fullScreenPage by remember { mutableStateOf<Int?>(null) }

    val openProgress by animateFloatAsState(
        targetValue = if (opened) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (reduceMotion) 0 else 720,
            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f),
        ),
        label = "passport_open",
    )

    val zone = remember { ZoneId.systemDefault() }
    val yFlights = remember(year, flights) {
        if (year == null) flights else flights.filter { Instant.ofEpochSecond(it.departTs).atZone(zone).year == year }
    }
    val yStamps = remember(year, stamps) {
        if (year == null) stamps else stamps.filter { s -> s.days.any { it.startsWith("$year-") } }
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("Cover", "Travel record", "Identity").forEachIndexed { idx, label ->
                val selected = when (idx) {
                    0 -> !opened
                    1 -> opened && pager.currentPage == 0
                    else -> opened && pager.currentPage == 1
                }
                Row(
                    Modifier
                        .clip(CircleShape)
                        .clickable {
                            when (idx) {
                                0 -> opened = false
                                1 -> {
                                    opened = true
                                    scope.launch { pager.animateScrollToPage(0) }
                                }
                                else -> {
                                    opened = true
                                    scope.launch { pager.animateScrollToPage(1) }
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val s = 0.965f + 0.035f * openProgress
                        scaleX = s
                        scaleY = s
                    },
            ) {
                HorizontalPager(
                    state = pager,
                    pageSpacing = 12.dp,
                    userScrollEnabled = openProgress > 0.95f,
                ) { page ->
                    when (page) {
                        0 -> SharablePassportCard(
                            year,
                            stamps,
                            flights,
                            tiltProvider = tiltProvider,
                            onTap = { if (opened) fullScreenPage = 0 else opened = true },
                        )
                        else -> SharableIdentityCard(
                            year,
                            stamps,
                            flights,
                            profile,
                            photoBytes,
                            tiltProvider = tiltProvider,
                            onTap = { if (opened) fullScreenPage = 1 else opened = true },
                        )
                    }
                }
                if (openProgress < 0.995f) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color.Black.copy(alpha = (1f - openProgress) * 0.42f))
                    )
                }
            }
            if (openProgress < 0.995f) {
                PassportCoverCard(
                    year = year,
                    countryCount = yStamps.size,
                    flightCount = yFlights.size,
                    tiltProvider = tiltProvider,
                    onOpen = { opened = true },
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            cameraDistance = 24f * density
                            rotationY = -112f * openProgress
                            alpha = 1f - ((openProgress - 0.58f) / 0.42f).coerceIn(0f, 1f)
                        },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (opened) "Tap passport to inspect full screen" else "Tap cover to open passport",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    fullScreenPage?.let { initial ->
        FullScreenPassportDialog(
            initialPage = initial,
            year = year,
            stamps = stamps,
            flights = flights,
            profile = profile,
            photoBytes = photoBytes,
            tiltProvider = tiltProvider,
            reduceMotion = reduceMotion,
            onDismiss = { fullScreenPage = null },
        )
    }
}

@Composable
private fun FullScreenPassportDialog(
    initialPage: Int,
    year: Int?,
    stamps: List<CountryStamp>,
    flights: List<Flight>,
    profile: HolderProfile?,
    photoBytes: ByteArray?,
    tiltProvider: () -> Offset,
    reduceMotion: Boolean,
    onDismiss: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = initialPage, pageCount = { 2 })
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val dragTilt = remember { mutableStateOf(Offset.Zero) }

    val effectiveTiltProvider = remember(tiltProvider, reduceMotion) {
        {
            val t = tiltProvider()
            if (reduceMotion) t else Offset(
                (t.x + dragTilt.value.x).coerceIn(-1f, 1f),
                (t.y + dragTilt.value.y).coerceIn(-1f, 1f),
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xF0090C16))
                .statusBarsPadding(),
        ) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = scale <= 1.02f,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 56.dp)
                        .pointerInput(page) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.2f) {
                                        scale = 1f
                                        pan = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                }
                            )
                        }
                        .pointerInput(page) {
                            detectTransformGestures { _, panChange, zoomChange, _ ->
                                val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
                                scale = nextScale
                                if (nextScale > 1.02f) {
                                    val maxX = size.width * (nextScale - 1f) / 2f
                                    val maxY = size.height * (nextScale - 1f) / 2f
                                    pan = Offset(
                                        (pan.x + panChange.x).coerceIn(-maxX, maxX),
                                        (pan.y + panChange.y).coerceIn(-maxY, maxY),
                                    )
                                } else {
                                    pan = Offset.Zero
                                }
                                if (!reduceMotion) {
                                    dragTilt.value = Offset(
                                        (dragTilt.value.x + panChange.x / 400f).coerceIn(-0.8f, 0.8f),
                                        (dragTilt.value.y + panChange.y / 400f).coerceIn(-0.8f, 0.8f),
                                    )
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val cardMod = Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = pan.x
                        translationY = pan.y
                    }
                    when (page) {
                        0 -> PassportCard(year, stamps, flights, modifier = cardMod, tiltProvider = effectiveTiltProvider)
                        else -> PassportIdentityCard(year, stamps, flights, profile, photoBytes, tiltProvider = effectiveTiltProvider, modifier = cardMod)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.14f)) {
                    val label = if (pager.currentPage == 0) "1 / 2  •  Travel record" else "2 / 2  •  Identity"
                    Text(
                        label,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Surface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.18f),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, "Close full screen", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
