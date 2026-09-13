package com.moviebox.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun TvScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        ctx.getSharedPreferences("moviebox", android.content.Context.MODE_PRIVATE)
    }
    var url by remember { mutableStateOf(prefs.getString("m3u_last", "") ?: "") }
    var channels by remember { mutableStateOf<List<ChannelInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        if (url.isBlank()) return
        scope.launch {
            loading = true
            error = null
            try {
                channels = Backend.m3u(url.trim())
                loaded = true
                prefs.edit().putString("m3u_last", url.trim()).apply()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp).then(modifier)) {
        Text("Live TV", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("M3U URL or /path/file.m3u") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { load() }, enabled = !loading) { Text("Load") }
        }
        Spacer(Modifier.height(8.dp))
        if (loading) CircularProgressIndicator()
        error?.let { Text(it, color = Color(0xFFF7768E), fontSize = 13.sp) }
        if (loaded) Text("${channels.size} channel(s)", fontSize = 13.sp, color = Color.Gray)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(channels) { ch ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (BuildConfig.FLAVOR == "vlc") {
                                if (!VlcPlayer.open(ctx, ch.url, emptyList(), ch.name)) {
                                    PlayerActivity.start(ctx, ch.url, emptyList(), ch.name, null)
                                }
                            } else {
                                PlayerActivity.start(ctx, ch.url, emptyList(), ch.name, null)
                            }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = ch.logo,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.name, fontSize = 14.sp)
                        if (ch.group.isNotEmpty()) {
                            Text(ch.group, fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                    Icon(Icons.Filled.PlayArrow, "Play")
                }
            }
        }
    }
}
