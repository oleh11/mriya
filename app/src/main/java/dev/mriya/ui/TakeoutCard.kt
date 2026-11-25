package dev.mriya.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings card: stream a Google Takeout `.zip` or Timeline `.json` file on-device. */
@Composable
fun TakeoutCard(
    ui: UiState,
    onPick: (Uri) -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(uri)
    }

    MriyaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Google Takeout", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(
                "Export Location History (Timeline) as JSON from takeout.google.com, or on your phone in Settings → Location → Timeline → Export. Only countries and flights are kept.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (ui.takeoutImporting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    ui.takeoutProgress ?: "Reading Takeout file…",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            } else {
                Button(
                    onClick = { picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (ui.takeout == null) "Import Takeout file" else "Re-import Takeout file",
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }

            ui.takeout?.let { t ->
                Text(
                    "Imported: ${t.stamps.size} ${if (t.stamps.size == 1) "country" else "countries"} · " +
                        "${t.flights.size} ${if (t.flights.size == 1) "flight" else "flights"} · " +
                        "${"%,d".format(t.points)} points",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!ui.takeoutImporting) {
                    OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Remove Takeout data")
                    }
                }
            }

            ui.takeoutMessage?.let { msg ->
                Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove Takeout data?") },
            text = { Text("Removes the country stamps and flights derived from your Google Takeout file. Dawarich sync and manually added flights stay.") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            },
        )
    }
}
