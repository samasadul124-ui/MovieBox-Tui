package com.moviebox.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
    var videoWarning by remember { mutableStateOf<String?>(null) }
    var attemptSubs by remember { mutableStateOf(true) }
    var retryTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(url, retryTick) {
        player = null // releases the previous player via DisposableEffect
        error = null
        videoWarning = null
        val useSubs = attemptSubs
        try {
            val subFile = withContext(Dispatchers.IO) {
                if (subUrl.isNullOrEmpty() || !useSubs) {
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
            val merged = subFile != null && mime != null
            val exo = ExoPlayer.Builder(ctx).build()
            // Cap at 480p and prefer H264: multi-res HEVC manifests stall or
            // fail on phones without strong HEVC decoders; 480p always plays.
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setMaxVideoSize(854, 480)
                .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
                .build()
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    // A merged source fails as a whole when its subtitle
                    // child errors, so retry once video-only before giving up.
                    if (useSubs && merged) {
                        attemptSubs = false
                        retryTick++
                    } else {
                        val cause = e.cause?.message?.let { " ($it)" } ?: ""
                        error = "Playback failed [${e.errorCodeName}]: ${e.message}$cause"
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    // Anti-silence: if the manifest offers video but the
                    // selector picked none (e.g. HEVC with no decoder on this
                    // phone), say so instead of showing unexplained black.
                    if (playbackState == Player.STATE_READY) {
                        var offered = false
                        var selected = false
                        for (g in exo.currentTracks.groups) {
                            if (g.type == C.TRACK_TYPE_VIDEO) {
                                offered = true
                                if (g.isSelected) selected = true
                            }
                        }
                        videoWarning =
                            if (offered && !selected) {
                                "Video format (HEVC) isn't supported by this phone — audio only."
                            } else {
                                null
                            }
                    }
                }
            })
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
            error != null -> PlayerErrorBox(error!!) {
                attemptSubs = true
                retryTick++
            }
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
        val vw = videoWarning
        if (p != null && error == null && vw != null) {
            Text(
                vw,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(16.dp)
                    .background(Color(0xAA000000))
                    .padding(8.dp)
            )
        }
    }
}

@Composable
private fun PlayerErrorBox(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Video failed to play",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Spacer(Modifier.height(8.dp))
            Text(message, color = Color(0xFFF7768E), fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Screenshot this message and send it — it tells exactly why.",
                color = Color.Gray, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
