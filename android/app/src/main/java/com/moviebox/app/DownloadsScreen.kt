package com.moviebox.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

data class DlRow(
    val id: Long,
    val name: String,
    val downloaded: Long,
    val total: Long?,
    val state: String, // active | exported | cancelled | failed
    val note: String
)

object DownloadManager {
    val rows = mutableStateMapOf<Long, DlRow>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun fileNameFor(title: String, url: String): String {
        var ext = url.substringBefore('?').substringAfterLast('.', "")
        if (ext.isEmpty() || ext.length > 4 || ext.any { !it.isLetterOrDigit() }) ext = "mp4"
        val stem = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(100)
            .ifEmpty { "video" }
        return "$stem.$ext"
    }

    fun start(
        ctx: Context,
        name: String,
        url: String,
        headers: List<Pair<String, String>>,
        onDone: (() -> Unit)? = null
    ) {
        val appCtx = ctx.applicationContext
        scope.launch {
            val dir = Backend.downloadDir(appCtx)
            dir.mkdirs()
            var dest = File(dir, name)
            var n = 1
            while (dest.exists()) {
                val base = name.substringBeforeLast('.', name)
                val ext = name.substringAfterLast('.', "")
                dest = if (ext.isNotEmpty()) File(dir, "${base}_$n.$ext")
                else File(dir, "${base}_$n")
                n++
            }
            val id: Long
            try {
                id = Backend.downloadStart(url, dest.absolutePath, headers)
            } catch (e: Exception) {
                toast(appCtx, "Download failed: ${e.message}")
                onDone?.invoke()
                return@launch
            }
            rows[id] = DlRow(id, name, 0, null, "active", dest.absolutePath)
            try {
                while (true) {
                    delay(400)
                    val st = try {
                        Backend.downloadStatus(id)
                    } catch (e: Exception) {
                        rows[id] = DlRow(id, name, 0, null, "failed", e.message ?: "")
                        break
                    }
                    val fin = st.finished
                    if (fin == null) {
                        rows[id] = rows[id]?.copy(
                            downloaded = st.downloaded,
                            total = st.total
                        ) ?: continue
                    } else {
                        val status = fin.optString("status", "failed")
                        if (status == "completed") {
                            val exported = exportToDownloads(appCtx, dest)
                            rows[id] = DlRow(id, name, st.downloaded, st.total, "exported", exported)
                            toast(appCtx, "Saved: $exported")
                        } else {
                            val msg = fin.optString("message", fin.optString("error", status))
                            rows[id] = DlRow(id, name, st.downloaded, st.total, status, msg)
                            toast(appCtx, "Download $status: $msg")
                        }
                        try {
                            Backend.downloadRelease(id)
                        } catch (_: Exception) {
                        }
                        break
                    }
                }
            } finally {
                onDone?.invoke()
            }
        }
    }

    fun cancel(id: Long) {
        scope.launch {
            try {
                Backend.downloadCancel(id)
            } catch (_: Exception) {
            }
        }
    }

    private fun exportToDownloads(ctx: Context, src: File): String {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeForDL(src.name))
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/MovieBox")
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return src.absolutePath
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) } 
                }
                "Downloads/MovieBox/${src.name}"
            } else {
                val ext = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: return src.absolutePath
                ext.mkdirs()
                val dst = File(ext, src.name)
                src.copyTo(dst, overwrite = true)
                dst.absolutePath
            }
        } catch (_: Exception) {
            src.absolutePath
        }
    }

    private fun mimeForDL(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            else -> "video/mp4"
        }

    private fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun DownloadsScreen(modifier: Modifier = Modifier) {
    val list = DownloadManager.rows.values.sortedBy { it.id }
    Column(Modifier.fillMaxSize().padding(12.dp).then(modifier)) {
        Text("Downloads", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) {
            Text(
                "No downloads yet. Pick a stream on any title and tap the download icon — " +
                    "finished files land in Downloads/MovieBox.",
                color = Color.Gray, fontSize = 13.sp
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (row.state == "active") {
                                        val t = row.total
                                        if (t != null && t > 0) {
                                            "${formatBytes(row.downloaded)} / ${formatBytes(t)}"
                                        } else {
                                            formatBytes(row.downloaded)
                                        }
                                    } else {
                                        "${row.state} — ${row.note}"
                                    },
                                    fontSize = 12.sp, color = Color.Gray
                                )
                            }
                            if (row.state == "active") {
                                Button(onClick = { DownloadManager.cancel(row.id) }) {
                                    Text("Cancel")
                                }
                            }
                        }
                        if (row.state == "active") {
                            Spacer(Modifier.height(6.dp))
                            val t = row.total
                            if (t != null && t > 0) {
                                LinearProgressIndicator(
                                    progress = {
                                        (row.downloaded.toFloat() / t).coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}
