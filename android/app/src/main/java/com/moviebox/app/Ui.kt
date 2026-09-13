package com.moviebox.app

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7AA2F7),
    secondary = Color(0xFFBB9AF7),
    background = Color(0xFF0B0E14),
    surface = Color(0xFF11151D),
    onBackground = Color(0xFFE6E9F0),
    onSurface = Color(0xFFE6E9F0)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}

@Composable
fun LoadingBox(text: String = "Loading…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(text, color = Color.Gray, fontSize = 13.sp)
        }
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(message, color = Color(0xFFF7768E), fontSize = 13.sp)
            if (onRetry != null) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
fun PosterCard(
    title: String,
    year: String?,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(onClick = onClick, modifier = modifier) {
        Column {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
            )
            Column(Modifier.padding(8.dp)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                if (!year.isNullOrEmpty()) {
                    Text(year, color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}

fun formatBytes(n: Long?): String {
    if (n == null || n <= 0) return "–"
    val gb = n.toDouble() / (1024 * 1024 * 1024)
    if (gb >= 1) return String.format("%.1f GB", gb)
    val mb = n.toDouble() / (1024 * 1024)
    if (mb >= 1) return String.format("%.0f MB", mb)
    return String.format("%d KB", n / 1024)
}

fun defaultProviderEnabled(id: String): Boolean = !id.startsWith("bdix_")

fun isProviderEnabled(ctx: Context, id: String): Boolean =
    ctx.getSharedPreferences("moviebox", Context.MODE_PRIVATE)
        .getBoolean("provider_$id", defaultProviderEnabled(id))

fun setProviderEnabled(ctx: Context, id: String, enabled: Boolean) {
    ctx.getSharedPreferences("moviebox", Context.MODE_PRIVATE)
        .edit().putBoolean("provider_$id", enabled).apply()
}
