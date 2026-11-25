package dev.mriya.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import dev.mriya.data.AdsbFeed
import dev.mriya.data.FlightLookupProvider

/**
 * Settings card for opt-in on-device live flight features:
 * - Schedule & aircraft lookup via user's own AeroDataBox or FlightAware key
 * - Live airborne tracking on the 3D globe via community ADS-B feeds
 */
@Composable
internal fun LiveFlightCard(vm: MainViewModel) {
    var provider by rememberSaveable { mutableStateOf(vm.flightLookupProvider) }
    var apiKey by remember { mutableStateOf(vm.flightLookupApiKey) }
    var host by rememberSaveable { mutableStateOf(vm.aeroDataBoxHost) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var savedNote by remember { mutableStateOf<String?>(null) }

    var adsbOn by rememberSaveable { mutableStateOf(vm.adsbEnabled) }
    var adsbFeed by rememberSaveable { mutableStateOf(vm.adsbFeed) }

    val shape = RoundedCornerShape(18.dp)

    MriyaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Live flight data (opt-in)", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(
                "All live lookups stay off until you choose to enable them. Your keys never leave this device or backups.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("FLIGHT LOOKUP (BYOK)", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 0.5.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FlightLookupProvider.entries) { p ->
                    Chip(
                        p.label,
                        Modifier.clickable { provider = p; savedNote = null },
                        selected = provider == p,
                    )
                }
            }
            if (provider != FlightLookupProvider.NONE) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; savedNote = null },
                    label = { Text("${provider.label} API key") },
                    singleLine = true,
                    shape = shape,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                if (showKey) "Hide lookup API key" else "Show lookup API key",
                            )
                        }
                    },
                )
                if (provider == FlightLookupProvider.AERODATABOX) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it; savedNote = null },
                        label = { Text("RapidAPI host") },
                        singleLine = true,
                        shape = shape,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    vm.saveFlightLookupConfig(provider, apiKey, host)
                    savedNote = if (provider == FlightLookupProvider.NONE) "Flight lookup turned off."
                    else "${provider.label} lookup saved encrypted."
                },
                enabled = provider == FlightLookupProvider.NONE || apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save lookup settings")
            }
            savedNote?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Live ADS-B globe tracking", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        "Free community ADS-B feed. No account or key needed. Only sends the flight callsign or tail # you track.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = adsbOn,
                    onCheckedChange = {
                        adsbOn = it
                        vm.setAdsbConfig(it, adsbFeed)
                    },
                )
            }
            if (adsbOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Feed:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    AdsbFeed.entries.forEach { f ->
                        Chip(
                            f.label,
                            Modifier.padding(end = 8.dp).clickable {
                                adsbFeed = f
                                vm.setAdsbConfig(adsbOn, f)
                            },
                            selected = adsbFeed == f,
                        )
                    }
                }
            }
        }
    }
}
