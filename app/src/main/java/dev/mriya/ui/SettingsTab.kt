package dev.mriya.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun SettingsScreen(vm: MainViewModel, done: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(vm.serverUrl) }
    var key by remember { mutableStateOf(vm.apiKey) }
    var legacyAuth by rememberSaveable { mutableStateOf(vm.legacyQueryAuth) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val urlError = if (url.isNotBlank() && !vm.urlAllowed(url)) "Server URL must start with https://" else null
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MriyaCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Connect Dawarich", fontWeight = FontWeight.Black, fontSize = 22.sp)
                OutlinedTextField(
                    url, { url = it }, label = { Text("Server URL") }, placeholder = { Text("https://dawarich.example.com") },
                    singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth(),
                    isError = urlError != null, supportingText = urlError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                OutlinedTextField(
                    key, { key = it }, label = { Text("API key") }, singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth(),
                    // Hidden by default; the eye toggles it. Password keyboard type keeps IMEs from learning or suggesting it.
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showKey) "Hide API key" else "Show API key")
                        }
                    },
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Legacy query-string auth", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "Off by default. Only enable for older Dawarich servers that reject Bearer tokens (sends API key in URL query).",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = legacyAuth,
                        onCheckedChange = {
                            legacyAuth = it
                            vm.legacyQueryAuth = it
                        },
                    )
                }
                Button(
                    onClick = { if (vm.saveSettings(url, key)) done() },
                    enabled = url.isNotBlank() && key.isNotBlank() && urlError == null,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text("Save & sync", fontWeight = FontWeight.ExtraBold) }
                Text("Find your API key in Dawarich under Settings → Account. It is stored encrypted on this device.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        BackgroundSyncCard()
        LiveFlightCard(vm)
        val ui by vm.state.collectAsStateWithLifecycle()
        MriyaCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Globe map", fontWeight = FontWeight.Black, fontSize = 18.sp)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Remove \"Mordor\" (Russia) from globe", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "Hides Russia from the 3D globe and redirects flights to Russia to the Moskva cruiser wreck site (45.18°N, 30.93°E).",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = ui.hideRussia,
                        onCheckedChange = vm::setHideRussia,
                    )
                }
            }
        }
        TakeoutCard(ui, vm::importTakeout, vm::cancelTakeoutImport, vm::removeTakeout)
        ProfileCard(ui, vm::saveProfile, vm::pickProfilePhoto, vm::deleteProfile)
        BackupCard(ui, vm::exportBackup, vm::importBackup)
        val hiddenStamps = ui.data.hiddenCountries.size
        val hiddenFlights = ui.data.flights.count { it.dismissed }
        if (hiddenStamps + hiddenFlights > 0) MriyaCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Removed items", fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("$hiddenStamps ${if (hiddenStamps == 1) "stamp" else "stamps"} and $hiddenFlights ${if (hiddenFlights == 1) "flight" else "flights"} are hidden. Your choices are remembered, also after a resync.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = vm::restoreAllHidden, modifier = Modifier.fillMaxWidth()) { Text("Restore everything") }
            }
        }
        MriyaCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cache", fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("Clears the passport stored on this device and syncs everything again from Dawarich.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { confirmClear = true }, enabled = vm.isConfigured, modifier = Modifier.fillMaxWidth()) { Text("Clear cache & full resync") }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear cache?") },
            text = { Text("Your Dawarich stamps and detected flights are rebuilt from your server. Hidden stamps, dismissed flights, manual imports, Google Takeout data, and your passport profile stay on this device. This can take a while on a large history.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearAndResync(); done() }) { Text("Clear & resync") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
