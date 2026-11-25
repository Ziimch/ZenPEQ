package com.zenpeq.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zenpeq.data.AutoEqEntry
import kotlinx.coroutines.CancellationException

private sealed interface IndexState {
    data object Loading : IndexState
    data class Ready(val entries: List<AutoEqEntry>) : IndexState
    data class Failed(val message: String) : IndexState
}

/** Search the AutoEq results and import a headphone's ParametricEQ preset. */
@Composable
internal fun AutoEqDialog(vm: EqViewModel, onDismiss: () -> Unit) {
    var state by remember { mutableStateOf<IndexState>(IndexState.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        state = IndexState.Loading
        state = try {
            IndexState.Ready(vm.autoEq.entries(forceRefresh = reload > 0))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            IndexState.Failed(e.message ?: "No connection")
        }
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) onDismiss() },
        title = { Text("AutoEq presets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search headphones or IEMs") },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear search") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                when (val s = state) {
                    IndexState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text("Loading list…", style = MaterialTheme.typography.bodySmall)
                    }
                    is IndexState.Failed -> {
                        Text(
                            "Couldn't load the list (${s.message}).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { reload++ }) { Text("Retry") }
                    }
                    is IndexState.Ready -> {
                        val results = remember(s.entries, query) { vm.autoEq.search(s.entries, query) }
                        when {
                            query.isBlank() -> Text(
                                "${s.entries.size} presets. Type a model name.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            results.isEmpty() -> Text(
                                "No match.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                items(results, key = { it.path }) { e ->
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .clickable(enabled = busy == null) {
                                                busy = e.path
                                                error = null
                                                vm.importAutoEq(e) { ok ->
                                                    busy = null
                                                    if (ok) onDismiss() else error = "Download failed"
                                                }
                                            }
                                            .padding(vertical = 10.dp),
                                    ) {
                                        Text(e.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            if (busy == e.path) "Downloading…" else "${e.source} · ${e.kind}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { reload++ }, enabled = busy == null) { Text("Refresh list") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = busy == null) { Text("Close") } },
    )
}
