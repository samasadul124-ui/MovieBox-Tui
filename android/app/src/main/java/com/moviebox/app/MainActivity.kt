package com.moviebox.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { MovieBoxApp() } }
    }
}

private enum class Tab { Search, Tv, Downloads, Settings }

@Composable
private fun MovieBoxApp() {
    val ctx = LocalContext.current
    var backendState by remember { mutableIntStateOf(0) } // 0 loading, 1 ready, 2 error
    var backendError by remember { mutableStateOf("") }
    var retryTick by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(Tab.Search) }
    var details by remember { mutableStateOf<Pair<String, String>?>(null) }

    val permLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(retryTick) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            Backend.ensureInit(ctx.applicationContext)
            backendState = 1
            // Unmissable version proof: confirms which build is really installed.
            android.widget.Toast.makeText(
                ctx,
                "MovieBox ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}) ready",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            backendError = e.message ?: "backend init failed"
            backendState = 2
        }
    }

    when (backendState) {
        0 -> LoadingBox("Starting backend…")
        2 -> ErrorBox(backendError) {
            backendState = 0
            retryTick++
        }
        else -> Scaffold(
            bottomBar = {
                if (details == null) {
                    NavigationBar {
                        val items = listOf(
                            Triple(Tab.Search, "Search", Icons.Filled.Search),
                            Triple(Tab.Tv, "TV", Icons.Filled.Tv),
                            Triple(Tab.Downloads, "Downloads", Icons.Filled.Download),
                            Triple(Tab.Settings, "Settings", Icons.Filled.Settings)
                        )
                        for ((t, label, icon) in items) {
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(icon, label) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        ) { pad ->
            val mod = Modifier.padding(pad)
            val d = details
            if (d != null) {
                DetailsScreen(d.first, d.second, { details = null }, mod)
            } else {
                when (tab) {
                    Tab.Search -> SearchScreen({ p, id -> details = p to id }, mod)
                    Tab.Tv -> TvScreen(mod)
                    Tab.Downloads -> DownloadsScreen(mod)
                    Tab.Settings -> SettingsScreen(mod)
                }
            }
        }
    }
}
