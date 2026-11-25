package dev.mriya.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mriya.domain.BackupCrypto
import java.time.LocalDate

/** Settings card: passphrase-encrypted backup & restore for user-entered data (never includes Dawarich API key). */
@Composable
fun BackupCard(
    ui: UiState,
    onExport: (Uri, String) -> Unit,
    onImport: (Uri, String) -> Unit,
) {
    var pendingExportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) pendingExportUri = uri
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImportUri = uri
    }

    MriyaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Backup", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(
                "All user data stays in app-private storage with cloud backup disabled. Export an encrypted backup of your stamps choices, imported flights, Takeout data, and holder profile before switching phones. Your Dawarich API key is never included.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (ui.backupBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Button(
                    onClick = { exportPicker.launch("mriya-backup-${LocalDate.now()}.mry") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Export encrypted backup", fontWeight = FontWeight.ExtraBold)
                }
                OutlinedButton(
                    onClick = { importPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Restore from backup")
                }
            }

            ui.backupMessage?.let { msg ->
                Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    pendingExportUri?.let { uri ->
        PassphraseDialog(
            title = "Encrypt backup",
            confirmLabel = "Export",
            requireConfirm = true,
            onDismiss = { pendingExportUri = null },
            onConfirm = { pass ->
                pendingExportUri = null
                onExport(uri, pass)
            },
        )
    }

    pendingImportUri?.let { uri ->
        PassphraseDialog(
            title = "Restore backup",
            confirmLabel = "Restore",
            requireConfirm = false,
            onDismiss = { pendingImportUri = null },
            onConfirm = { pass ->
                pendingImportUri = null
                onImport(uri, pass)
            },
        )
    }
}

@Composable
private fun PassphraseDialog(
    title: String,
    confirmLabel: String,
    requireConfirm: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val tooShort = pass.isNotEmpty() && pass.length < BackupCrypto.MIN_PASSPHRASE_LENGTH
    val mismatch = requireConfirm && confirm.isNotEmpty() && pass != confirm
    val ready = pass.length >= BackupCrypto.MIN_PASSPHRASE_LENGTH && (!requireConfirm || pass == confirm)
    val shape = RoundedCornerShape(16.dp)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (requireConfirm) "Choose a passphrase (at least ${BackupCrypto.MIN_PASSPHRASE_LENGTH} characters). Without it, the backup cannot be opened."
                    else "Enter the passphrase used when this backup was created.",
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Passphrase") }, singleLine = true, shape = shape,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = tooShort,
                    supportingText = if (tooShort) { { Text("At least ${BackupCrypto.MIN_PASSPHRASE_LENGTH} characters") } } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (requireConfirm) {
                    OutlinedTextField(
                        confirm, { confirm = it }, label = { Text("Confirm passphrase") }, singleLine = true, shape = shape,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        isError = mismatch,
                        supportingText = if (mismatch) { { Text("Passphrases do not match") } } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pass) }, enabled = ready) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
