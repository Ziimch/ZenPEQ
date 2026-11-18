package com.zenpeq.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenpeq.data.ImportPreview
import com.zenpeq.data.ProfileIo
import com.zenpeq.model.EqProfile

/** Profile list with real-time search and a clear (X) button. */
@Composable
internal fun ProfilePickerDialog(
    profiles: List<EqProfile>,
    activeId: String,
    iemId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(profiles, query) {
        val q = query.trim()
        if (q.isEmpty()) profiles else profiles.filter { it.name.contains(q, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profiles (${profiles.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search profiles") },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear search") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shown.isEmpty()) {
                    Text("No matching profiles", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(shown, key = { it.id }) { p ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onSelect(p.id); onDismiss() }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    p.name, Modifier.weight(1f),
                                    color = if (p.id == activeId) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                if (p.id == iemId) Icon(Icons.Default.Star, "IEM profile", Modifier.padding(horizontal = 4.dp))
                                if (p.id == activeId) Icon(Icons.Default.Check, "Active", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun ImportDialog(
    preview: (String) -> ImportPreview,
    onDismiss: () -> Unit,
    onConfirm: (name: String, preview: ImportPreview) -> Unit,
) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("Imported EQ") }
    val result = remember(text) { preview(text) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            text = readText(ctx, uri)
            displayName(ctx, uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }?.let { name = it }
        }
    }
    val canImport = when (result) {
        is ImportPreview.Apo -> result.result.filters.isNotEmpty() || result.result.preampDb != null
        is ImportPreview.JsonProfiles -> result.profiles.isNotEmpty()
        else -> false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open .json / .txt file")
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("…or paste Equalizer APO / AutoEQ text or JSON") },
                    placeholder = { Text("Preamp: -3.0 dB\nFilter 1: ON PK Fc 4500 Hz Gain -3.5 dB Q 2.5") },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
                if (result is ImportPreview.Apo) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Profile name") })
                }
                when (result) {
                    is ImportPreview.Apo -> {
                        val r = result.result
                        val pre = r.preampDb?.let { ", preamp ${"%.1f".format(it)} dB" } ?: ""
                        Text("Equalizer APO text: ${r.filters.size} filter(s)$pre", style = MaterialTheme.typography.bodySmall)
                        r.warnings.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    is ImportPreview.JsonProfiles -> Text(
                        "JSON: ${result.profiles.size} profile(s) — ${result.profiles.joinToString { it.name }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is ImportPreview.Invalid -> Text(
                        result.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                    ImportPreview.Empty -> Unit
                }
            }
        },
        confirmButton = { TextButton(enabled = canImport, onClick = { onConfirm(name, result) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    JSON("JSON", "json", "application/json"),
    APO("Equalizer APO .txt", "txt", "text/plain"),
}

@Composable
internal fun ExportDialog(profile: EqProfile, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var format by remember { mutableStateOf(ExportFormat.JSON) }
    val content = remember(profile, format) {
        if (format == ExportFormat.JSON) ProfileIo.toJson(profile) else ProfileIo.toApoText(profile)
    }
    val fileName = ProfileIo.fileName(profile, format.ext)
    val jsonSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { writeText(ctx, it, content) }
    }
    val txtSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { writeText(ctx, it, content) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export “${profile.name}”") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    ExportFormat.entries.forEach { f ->
                        FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Text(
                    content,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, fileName)
                        putExtra(Intent.EXTRA_TEXT, content)
                    }
                    ctx.startActivity(Intent.createChooser(send, "Share profile"))
                }) { Text("Share") }
                TextButton(onClick = {
                    if (format == ExportFormat.JSON) jsonSaver.launch(fileName) else txtSaver.launch(fileName)
                }) { Text("Save file") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun readText(ctx: Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(200_000) }
    }.getOrNull().orEmpty()

private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull()

private fun writeText(ctx: Context, uri: Uri, text: String) {
    val ok = runCatching {
        checkNotNull(ctx.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(text) }
    }.isSuccess
    Toast.makeText(ctx, if (ok) "Saved" else "Could not save file", Toast.LENGTH_SHORT).show()
}
