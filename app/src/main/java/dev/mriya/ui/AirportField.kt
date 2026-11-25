package dev.mriya.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mriya.domain.AirportDb
import java.util.Locale

/**
 * An airport picker: type a city ("Taipei"), an airport name ("O'Hare") or a code ("ORD"), then tap a suggestion.
 * [value] is the chosen IATA code, or blank while nothing valid is chosen. Matching is local (the bundled airport list).
 */
@Composable
fun AirportField(label: String, value: String, onValueChange: (String) -> Unit, db: AirportDb?, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf(value) }
    // A code set from outside (for example by a flight lookup) shows up in the field.
    LaunchedEffect(value) { if (value.isNotBlank() && value != query.trim().uppercase(Locale.ROOT)) query = value }
    val typed = query.trim()
    val chosen = if (typed.length == 3) db?.byIata(typed) else null
    val suggestions = remember(typed, db) { if (db == null || typed.length < 2 || chosen != null) emptyList() else db.search(typed, 5) }
    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { q ->
                query = q
                val t = q.trim()
                onValueChange(if (t.length == 3 && (db == null || db.isAirport(t))) t.uppercase(Locale.ROOT) else "")
            },
            label = { Text(label) },
            placeholder = { Text("City or code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            supportingText = { Text(chosen?.let { "${it.iata}: ${it.name}" + if (it.city.isNotBlank()) ", ${it.city}" else "" } ?: "Pick from the list") },
            modifier = Modifier.fillMaxWidth(),
        )
        suggestions.forEach { a ->
            Row(Modifier.fillMaxWidth().clickable { query = a.iata; onValueChange(a.iata) }.padding(vertical = 6.dp, horizontal = 4.dp)) {
                Text(a.iata, fontWeight = FontWeight.Black, fontSize = 14.sp, modifier = Modifier.padding(end = 10.dp))
                Text(
                    listOf(a.city, a.name).filter { it.isNotBlank() }.joinToString(": ") + "  •  " + a.country,
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
    }
}
