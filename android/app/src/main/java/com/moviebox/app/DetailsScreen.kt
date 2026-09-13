package com.moviebox.app

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(
    provider: String,
    id: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<MediaDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var season by remember { mutableIntStateOf(1) }
    var episode by remember { mutableIntStateOf(1) }
    var releases by remember { mutableStateOf<List<ReleaseInfo>>(emptyList()) }
    var streamsLoading by remember { mutableStateOf(false) }
    var streamsError by remember { mutableStateOf<String?>(null) }
    var subs by remember { mutableStateOf<List<SubtitleInfo>>(emptyList()) }
    var subIndex by remember { mutableIntStateOf(0) }
    var subMenu by remember { mutableStateOf(false) }

    suspend fun loadStreams(d: MediaDetails, s: Int, e: Int) {
        streamsLoading = true
        streamsError = null
        releases = emptyList()
        try {
            val series = d.isSeries()
            val rs = if (series) s else 0
            val re = if (series) e else 0
            val rel = Backend.streams(provider, d.id.value, rs, re, series)
            releases = rel
            val rid = rel.firstOrNull()?.resourceId
            subs = if (!rid.isNullOrEmpty()) {
                try {
                    Backend.subtitles(d.id.value, rid, rs, re)
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            subIndex = subs.indexOfFirst { it.name.equals("English", ignoreCase = true) }
                .takeIf { it >= 0 } ?: 0
        } catch (e: Exception) {
            streamsError = e.message
        } finally {
            streamsLoading = false
        }
    }

    LaunchedEffect(provider, id) {
        try {
            val d = Backend.details(provider, id)
            details = d
            var ls = 1
            var le = 1
            if (d.isSeries()) {
                val s0 = d.seasons.firstOrNull()
                ls = s0?.number ?: 1
                le = s0?.episodes?.firstOrNull()?.number ?: 1
                season = ls
                episode = le
            }
            loadStreams(d, ls, le)
        } catch (e: Exception) {
            error = e.message
        }
    }

    fun play(rel: ReleaseInfo) {
        val m = rel.mirrors.firstOrNull() ?: return
        val d = details ?: return
        val sub = subs.getOrNull(subIndex)?.takeIf { it.url.isNotEmpty() }
        PlayerActivity.start(ctx, m.url, m.headers, d.title, sub?.url)
    }

    fun download(rel: ReleaseInfo) {
        val m = rel.mirrors.firstOrNull() ?: return
        val d = details ?: return
        if (m.url.contains(".mpd") || m.url.contains("/dash/")) {
            Toast.makeText(ctx, "DASH streams are play-only", Toast.LENGTH_SHORT).show()
            return
        }
        val name = DownloadManager.fileNameFor(
            d.title + if (d.isSeries()) " S${season}E${episode}" else "",
            m.url
        )
        DownloadService.start(ctx, name, m.url, m.headers)
        Toast.makeText(ctx, "Download started", Toast.LENGTH_SHORT).show()
    }

    val d = details
    if (d == null && error == null) {
        LoadingBox("Loading details…")
        return
    }
    error?.let {
        Column(Modifier.fillMaxSize().padding(16.dp).then(modifier)) {
            Button(onClick = onBack) { Text("Back") }
            Spacer(Modifier.height(16.dp))
            ErrorBox(it)
        }
        return
    }
    d ?: return

    LazyColumn(Modifier.fillMaxSize().padding(12.dp).then(modifier)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                Text(
                    d.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(
                    model = d.posterUrl,
                    contentDescription = d.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(130.dp).height(190.dp)
                )
                Column {
                    Text(listOfNotNull(d.year, d.imdbRating?.let { "★ $it" }, d.duration)
                        .joinToString("  ·  "), color = Color.Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    if (d.genres.isNotEmpty()) {
                        Text(d.genres.joinToString(", "), fontSize = 13.sp, color = Color.Gray)
                    }
                    Spacer(Modifier.height(6.dp))
                    d.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it.take(400), fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (d.isSeries()) {
            item {
                Text("Season", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    for (s in d.seasons) {
                        FilterChip(
                            selected = season == s.number,
                            onClick = {
                                season = s.number
                                episode = s.episodes.firstOrNull()?.number ?: 1
                                scope.launch { loadStreams(d, season, episode) }
                            },
                            label = { Text("S${s.number}") }
                        )
                    }
                }
                Text("Episode", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    val eps = d.seasons.firstOrNull { it.number == season }?.episodes
                        ?: emptyList()
                    for (e in eps) {
                        FilterChip(
                            selected = episode == e.number,
                            onClick = {
                                episode = e.number
                                scope.launch { loadStreams(d, season, e.number) }
                            },
                            label = { Text("${e.number}") }
                        )
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Streams", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(12.dp))
                if (streamsLoading) CircularProgressIndicator(modifier = Modifier.height(20.dp))
            }
            streamsError?.let { Text(it, color = Color(0xFFF7768E), fontSize = 13.sp) }
            if (subs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ExposedDropdownMenuBox(
                    expanded = subMenu,
                    onExpandedChange = { subMenu = !subMenu }
                ) {
                    TextField(
                        value = subs.getOrNull(subIndex)?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Subtitles") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(subMenu)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = subMenu,
                        onDismissRequest = { subMenu = false }
                    ) {
                        subs.forEachIndexed { i, s ->
                            DropdownMenuItem(
                                text = { Text(s.name) },
                                onClick = {
                                    subIndex = i
                                    subMenu = false
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        items(releases) { rel ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(rel.quality ?: "Auto", style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(
                                rel.mirrors.firstOrNull()?.label?.takeIf { it.isNotBlank() },
                                rel.language,
                                formatBytes(rel.sizeBytes).takeIf { it != "–" }
                            ).joinToString("  ·  "),
                            fontSize = 12.sp, color = Color.Gray
                        )
                    }
                    IconButton(onClick = { play(rel) }) {
                        Icon(Icons.Filled.PlayArrow, "Play")
                    }
                    IconButton(onClick = { download(rel) }) {
                        Icon(Icons.Filled.Download, "Download")
                    }
                }
            }
        }
    }
}
