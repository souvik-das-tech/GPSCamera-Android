// Asks for the company name, logo, event name and note printed on every photo and video.
package com.bbcl.gpscamera

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.io.File

@Composable
fun BrandDialog(current: Brand, firstLaunch: Boolean, onDismiss: () -> Unit, onSave: (Brand) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(current.company) }
    var event by remember { mutableStateOf(current.event) }
    var note by remember { mutableStateOf(current.note) }
    var logo by remember { mutableStateOf(current.logoPath) }
    val logoBitmap = remember(logo) { decodeLogo(logo)?.asImageBitmap() }

    fun discardPicked() {
        // A logo picked in this dialog but not kept.
        if (logo != null && logo != current.logoPath) File(logo!!).delete()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            importLogo(context, uri)?.let {
                discardPicked()
                logo = it
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!firstLaunch) { discardPicked(); onDismiss() } },
        properties = DialogProperties(dismissOnClickOutside = !firstLaunch, dismissOnBackPress = !firstLaunch),
        title = { Text("Company, event & note") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (firstLaunch) {
                    Text(
                        "Your company name, logo, event name and note will be printed on every photo and video. " +
                            "You can change them later from the top bar.",
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                Field(name, { name = it.take(40) }, "Company name", "e.g. BBCL Constructions", words = true)
                Field(event, { event = it.take(60) }, "Event name", "e.g. Annual Meet 2026", words = true, clearable = true)
                Field(note, { note = it.take(80) }, "Note", "e.g. Site visit, plot 12", clearable = true)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (logoBitmap != null) {
                            Image(logoBitmap, contentDescription = "Logo", contentScale = ContentScale.Fit, modifier = Modifier.size(72.dp))
                        } else {
                            Icon(Icons.Filled.Business, contentDescription = null, tint = Color.Black.copy(alpha = 0.38f), modifier = Modifier.size(36.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        OutlinedButton(onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (logo == null) "Choose logo" else "Change logo")
                        }
                        if (logo != null) {
                            TextButton(onClick = { discardPicked(); logo = null }) { Text("Remove logo") }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { discardPicked(); onDismiss() }) { Text(if (firstLaunch) "Skip" else "Cancel") }
        },
        confirmButton = {
            Button(onClick = { onSave(Brand(name.trim(), logo, event.trim(), note.trim())) }) { Text("Save") }
        },
    )
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    hint: String,
    words: Boolean = false,
    clearable: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = if (words) KeyboardCapitalization.Words else KeyboardCapitalization.Sentences),
        trailingIcon = if (clearable && value.isNotEmpty()) {
            { IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear $label") } }
        } else null,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    )
}
