package dev.mriya.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mriya.data.HolderProfile
import dev.mriya.domain.ProfileUtils

/** Settings card for editing the optional, encrypted on-device passport holder profile and photo. */
@Composable
fun ProfileCard(
    ui: UiState,
    onSave: (HolderProfile) -> Unit,
    onPickPhoto: (Uri) -> Unit,
    onDelete: () -> Unit,
) {
    val existing = ui.profile
    var givenNames by rememberSaveable(existing) { mutableStateOf(existing?.givenNames ?: "") }
    var surname by rememberSaveable(existing) { mutableStateOf(existing?.surname ?: "") }
    var nationality by rememberSaveable(existing) { mutableStateOf(existing?.nationality ?: "") }
    var dob by rememberSaveable(existing) { mutableStateOf(existing?.dateOfBirth ?: "") }
    var pob by rememberSaveable(existing) { mutableStateOf(existing?.placeOfBirth ?: "") }
    var passportNo by rememberSaveable(existing) {
        mutableStateOf(existing?.passportNumber?.takeIf { it.isNotBlank() } ?: ProfileUtils.generatePassportNumber())
    }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(existing) {
        if (existing != null) {
            givenNames = existing.givenNames
            surname = existing.surname
            nationality = existing.nationality
            dob = existing.dateOfBirth
            pob = existing.placeOfBirth
            if (existing.passportNumber.isNotBlank()) passportNo = existing.passportNumber
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPickPhoto(uri)
    }

    val photoBitmap = remember(ui.profilePhoto) {
        ui.profilePhoto?.bytes?.let { b ->
            runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(18.dp)

    MriyaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Passport holder", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(
                "Optional personal details for your passport identity page. Stored encrypted on this device and never uploaded.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(68.dp),
                ) {
                    if (photoBitmap != null) {
                        Image(
                            bitmap = photoBitmap,
                            contentDescription = "Passport portrait",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, "No portrait", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                OutlinedButton(
                    onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                ) {
                    Text(if (photoBitmap == null) "Choose photo" else "Change photo")
                }
            }
            ui.profileMessage?.let { msg ->
                Text(msg, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            }

            OutlinedTextField(
                givenNames, { givenNames = it }, label = { Text("Given names") },
                singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
            OutlinedTextField(
                surname, { surname = it }, label = { Text("Surname") },
                singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
            OutlinedTextField(
                nationality, { nationality = it }, label = { Text("Nationality") }, placeholder = { Text("e.g. UKR") },
                singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            OutlinedTextField(
                dob, { dob = it }, label = { Text("Date of birth") }, placeholder = { Text("YYYY-MM-DD") },
                singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                pob, { pob = it }, label = { Text("Place of birth") },
                singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
            OutlinedTextField(
                passportNo, {}, label = { Text("Passport no. (decorative)") },
                readOnly = true, singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { passportNo = ProfileUtils.generatePassportNumber() }) {
                        Icon(Icons.Default.Refresh, "New passport number")
                    }
                },
            )

            Button(
                onClick = {
                    onSave(
                        HolderProfile(
                            givenNames = givenNames.trim(),
                            surname = surname.trim(),
                            nationality = nationality.trim(),
                            dateOfBirth = dob.trim(),
                            placeOfBirth = pob.trim(),
                            passportNumber = passportNo,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("Save profile", fontWeight = FontWeight.ExtraBold)
            }

            if (existing != null || photoBitmap != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete profile")
                }
            }

            ui.profileMessage?.let { msg ->
                Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete passport profile?") },
            text = { Text("Erases your saved holder details and encrypted portrait photo from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    givenNames = ""; surname = ""; nationality = ""; dob = ""; pob = ""
                    passportNo = ProfileUtils.generatePassportNumber()
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
