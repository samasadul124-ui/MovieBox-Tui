package com.moviebox.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class PlayerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_HEADERS = "headers"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUB = "sub"

        fun start(
            ctx: Context,
            url: String,
            headers: List<Pair<String, String>>,
            title: String,
            subUrl: String?
        ) {
            val ha = JSONArray()
            for ((k, v) in headers) ha.put(JSONArray().put(k).put(v))
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java).apply {
                    putExtra(EXTRA_URL, url)
                    putExtra(EXTRA_HEADERS, ha.toString())
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_SUB, subUrl)
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        val headers = mutableListOf<Pair<String, String>>()
        try {
            val ha = JSONArray(intent.getStringExtra(EXTRA_HEADERS) ?: "[]")
            for (i in 0 until ha.length()) {
                val p = ha.getJSONArray(i)
                if (p.length() >= 2) headers.add(p.getString(0) to p.getString(1))
            }
        } catch (_: Exception) {
        }
        val sub = intent.getStringExtra(EXTRA_SUB)
        setContent { AppTheme { PlayerScreen(url, headers, sub) } }
    }
}

private fun mimeFor(name: String): String? = when (
    name.substringBefore('?').substringAfterLast('.', "").lowercase()
) {
    "srt" -> MimeTypes.APPLICATION_SUBRIP
    "vtt", "webvtt" -> MimeTypes.TEXT_VTT
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    else -> null
}

/** Best-effort subtitle fetch (same headers as playback). Null on any failure. */
private fun fetchSubtitle(
    ctx: Context,
    url: String,
    headers: List<Pair<String, String>>
): File? {
    return try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        if (conn.responseCode != 200) return null
        var ext = url.substringBefore('?').substringAfterLast('.', "srt")
        if (ext.length > 4 || ext.any { !it.isLetterOrDigit() }) ext = "srt"
        val dir = File(ctx.cacheDir, "mb_subs").apply { mkdirs() }
        val out = File(dir, "sub_${System.currentTimeMillis()}.$ext")
        conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        if (out.length() > 0) out else null
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun PlayerScreen(
    url: String,
    headers: List<Pair<String, String>>,
    subUrl: String?
) {
    val ctx = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(url) {
        try {
            val subFile = withContext(Dispatchers.IO) {
                if (subUrl.isNullOrEmpty()) {
                    null
                } else {
                    try {
                        fetchSubtitle(ctx, subUrl, headers)
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            val dsFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers.toMap())
                .setAllowCrossProtocolRedirects(true)
            // Explicit format routing (same idea as detectFormat() in the web
            // prototype): .mpd -> DASH, .m3u8 -> HLS, else progressive.
            // Referencing these classes here also guarantees a COMPILE error
            // (not a crash on your phone) if a module ever goes missing.
            val uri = Uri.parse(url)
            val path = (uri.path ?: url).lowercase()
            val item = MediaItem.fromUri(uri)
            val base: MediaSource = when {
                ".mpd" in path ->
                    DashMediaSource.Factory(dsFactory).createMediaSource(item)
                ".m3u8" in path ->
                    HlsMediaSource.Factory(dsFactory).createMediaSource(item)
                else ->
                    ProgressiveMediaSource.Factory(dsFactory).createMediaSource(item)
            }
            val mime = subFile?.let { mimeFor(it.name) }
            val source: MediaSource = if (subFile != null && mime != null) {
                val sub = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subFile))
                    .setMimeType(mime)
                    .setLanguage("und")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                val subSource = SingleSampleMediaSource.Factory(dsFactory)
                    .createMediaSource(sub, C.TIME_UNSET)
                MergingMediaSource(base, subSource)
            } else {
                base
            }
            val exo = ExoPlayer.Builder(ctx).build()
            exo.setMediaSource(source)
            exo.prepare()
            exo.playWhenReady = true
            player = exo
        } catch (e: Exception) {
            error = e.message
        }
    }
    DisposableEffect(player) {
        onDispose {
            try {
                player?.release()
            } catch (_: Exception) {
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val p = player
        when {
            error != null -> ErrorBox(error!!)
            p != null -> AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        this.player = p
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            else -> LoadingBox("Loading stream…")
        }
    }
}
