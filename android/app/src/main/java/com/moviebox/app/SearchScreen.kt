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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(onOpen: (provider: String, id: String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers = remember {
        PROVIDERS.filter { isProviderEnabled(ctx, it.first) }.ifEmpty { PROVIDERS }
    }
    var provider by remember { mutableStateOf(providers.first().first) }
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var results by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var rail by remember { mutableStateOf<List<CatalogItem>?>(null) }

    LaunchedEffect(Unit) {
        try {
            rail = Backend.homepage("2", 1)
        } catch (_: Exception) {
            rail = emptyList()
        }
    }
    LaunchedEffect(query) {
        if (query.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        val q = query
        delay(450)
        if (q != query) return@LaunchedEffect
        try {
            suggestions = Backend.suggest(q).take(8)
        } catch (_: Exception) {
        }
    }

    fun doSearch(reset: Boolean) {
        if (query.isBlank()) return
        scope.launch {
            loading = true
            error = null
            try {
                val p = if (reset) 1 else page + 1
                val r = Backend.search(provider, query.trim(), p)
                results = if (reset) r else results + r
                page = p
                searched = true
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp).then(modifier)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            for ((id, label) in providers) {
                FilterChip(
                    selected = provider == id,
                    onClick = { provider = id },
                    label = { Text(label) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search movies, series…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch(true) }),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { doSearch(true) }, enabled = !loading) { Text("Go") }
        }
        if (suggestions.isNotEmpty() && !searched) {
            Column(Modifier.padding(vertical = 4.dp)) {
                for (s in suggestions) {
                    Text(
                        s,
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                query = s
                                suggestions = emptyList()
                                doSearch(true)
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (loading && results.isEmpty()) {
            CircularProgressIndicator()
        }
        error?.let { Text(it) }
        if (!searched) {
            Text("Browse")
            Spacer(Modifier.height(4.dp))
            val items = rail
            if (items == null) {
                CircularProgressIndicator()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items) { item ->
                        PosterCard(item.title, item.year, item.posterUrl, {
                            onOpen(item.id.provider, item.id.value)
                        })
                    }
                }
            }
        } else {
            Text("${results.size} result(s)")
            Spacer(Modifier.height(4.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(results) { item ->
                    PosterCard(item.title, item.year, item.posterUrl, {
                        onOpen(item.id.provider, item.id.value)
                    })
                }
            }
            Button(
                onClick = { doSearch(false) },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (loading) "Loading…" else "Load more") }
        }
    }
}
