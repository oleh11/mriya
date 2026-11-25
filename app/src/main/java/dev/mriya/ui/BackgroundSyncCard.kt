package dev.mriya.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mriya.sync.SyncScheduler

/** Settings card: opt-in periodic background sync. */
@Composable
fun BackgroundSyncCard() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(SyncScheduler.isPeriodicEnabled(ctx)) }
    MriyaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Background sync", fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it; SyncScheduler.setPeriodic(ctx, it) })
            }
            Text(
                "Off by default. When on, Mriya fetches new points from your Dawarich server about every 6 hours while online and the battery is not low.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Asks for the notification permission (Android 13+) once, the first time a sync starts, so the sync's
 * progress notification can show. Declining is fine: the sync runs either way.
 */
@Composable
fun NotificationPermissionRequest(vm: MainViewModel) {
    val ctx = LocalContext.current
    val ui by vm.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(ui.syncing) {
        if (!ui.syncing || Build.VERSION.SDK_INT < 33) return@LaunchedEffect
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && !prefs.getBoolean("notif_permission_asked", false)) {
            prefs.edit { putBoolean("notif_permission_asked", true) }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
