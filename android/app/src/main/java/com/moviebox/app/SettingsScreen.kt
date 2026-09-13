package com.moviebox.app

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val providerStates = remember {
        mutableStateMapOf(
            *PROVIDERS.map { it.first to isProviderEnabled(ctx, it.first) }.toTypedArray()
        )
    }
    var addons by remember { mutableStateOf(AddonsStore.load(ctx)) }
    var showAdd by remember { mutableStateOf(false) }
    var newUrl by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            val v = withContext(Dispatchers.IO) { Backend.version() }
            version = listOfNotNull(
                v.optString("version", "").takeIf { it.isNotEmpty() },
                v.optString("git", "").takeIf { it.isNotEmpty() }?.let { "($it)" },
                v.optString("profile", "").takeIf { it.isNotEmpty() }
            ).joinToString(" ")
        } catch (_: Exception) {
            version = "unknown"
        }
    }

    fun refreshAddons() {
        addons = AddonsStore.load(ctx)
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp).then(modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Providers", style = MaterialTheme.typography.titleMedium)
        }
        items(PROVIDERS) { (id, label) ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, fontSize = 14.sp)
                    if (id.startsWith("bdix_")) {
                        Text(
                            "Needs a Bangladeshi (BDIX) connection",
                            fontSize = 11.sp, color = Color.Gray
                        )
                    }
                }
                Switch(
                    checked = providerStates[id] == true,
                    onCheckedChange = {
                        providerStates[id] = it
                        setProviderEnabled(ctx, id, it)
                    }
                )
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stremio addons", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, "Add addon")
                }
            }
            Text(
                "Same format as the desktop app. Changes apply to new searches/streams.",
                fontSize = 12.sp, color = Color.Gray
            )
        }
        items(addons, key = { it.manifestUrl }) { addon ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(addon.name.ifEmpty { addon.manifestUrl }, fontSize = 14.sp)
                        Text(addon.manifestUrl, fontSize = 11.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = addon.enabled,
                        onCheckedChange = {
                            addon.enabled = it
                            AddonsStore.save(ctx, addons)
                            refreshAddons()
                        }
                    )
                    IconButton(onClick = {
                        AddonsStore.save(ctx, addons - addon)
                        refreshAddons()
                    }) {
                        Icon(Icons.Filled.Delete, "Remove")
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("Backend", style = MaterialTheme.typography.titleMedium)
            Text("moviebox $version", fontSize = 13.sp, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    try {
                        Backend.cacheDir(ctx).deleteRecursively()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "Cache cleared", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }) {
                Text("Clear cache")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "MovieBox for Android — same Rust backend as the desktop app. " +
                    "Search, details, streams, subtitles, downloads, live TV and " +
                    "Stremio addons all run on-device.",
                fontSize = 12.sp, color = Color.Gray
            )
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add Stremio addon") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        label = { Text("Manifest URL (…/manifest.json)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name (optional)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = newUrl.trim()
                    if (!u.startsWith("http")) {
                        Toast.makeText(ctx, "Enter an http(s) manifest URL", Toast.LENGTH_SHORT)
                            .show()
                        return@TextButton
                    }
                    val list = AddonsStore.load(ctx)
                    if (list.none { it.manifestUrl == u }) {
                        list.add(
                            InstalledAddon(
                                manifestUrl = u,
                                name = newName.trim().ifEmpty { u },
                                enabled = true,
                                providesCatalog = true,
                                providesMeta = true,
                                providesStream = true
                            )
                        )
                        AddonsStore.save(ctx, list)
                    }
                    newUrl = ""
                    newName = ""
                    showAdd = false
                    refreshAddons()
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            }
        )
    }
}
