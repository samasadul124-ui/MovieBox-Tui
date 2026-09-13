package com.moviebox.app

import android.content.Context
import com.moviebox.core.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BackendException(message: String) : Exception(message)

/** Provider id -> display label. Ids must match Rust ProviderKind::parse. */
val PROVIDERS = listOf(
    "moviebox" to "MovieBox",
    "fourkhdhub" to "4KHDHub",
    "bdix_circleftp" to "CircleFTP",
    "bdix_dhakaflix" to "DhakaFlix",
    "addons" to "Addons"
)

data class MediaId(val provider: String, val value: String)
data class CatalogItem(
    val id: MediaId,
    val title: String,
    val mediaType: String,
    val year: String?,
    val posterUrl: String?
)

data class EpisodeInfo(val season: Int, val number: Int, val title: String?)
data class SeasonInfo(val number: Int, val episodes: List<EpisodeInfo>)
data class DubInfo(val subjectId: String, val language: String, val label: String)

data class MediaDetails(
    val id: MediaId,
    val title: String,
    val mediaType: String,
    val year: String?,
    val description: String?,
    val imdbRating: String?,
    val posterUrl: String?,
    val duration: String?,
    val genres: List<String>,
    val seasons: List<SeasonInfo>,
    val dubs: List<DubInfo>
) {
    fun isSeries(): Boolean = mediaType == "series" || seasons.isNotEmpty()
}

data class Mirror(val label: String, val url: String, val headers: List<Pair<String, String>>)
data class ReleaseInfo(
    val filename: String,
    val quality: String?,
    val language: String?,
    val sizeBytes: Long?,
    val mirrors: List<Mirror>,
    val resourceId: String?
)

data class SubtitleInfo(val name: String, val url: String)
data class ChannelInfo(
    val id: String,
    val name: String,
    val logo: String,
    val group: String,
    val url: String
)

private fun JSONObject.optStr(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.optLongObj(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun parseMediaId(o: JSONObject): MediaId =
    MediaId(o.getString("provider"), o.getString("value"))

private fun parseCatalogItem(o: JSONObject): CatalogItem = CatalogItem(
    id = parseMediaId(o.getJSONObject("id")),
    title = o.getString("title"),
    mediaType = o.optStr("media_type") ?: "movie",
    year = o.optStr("year"),
    posterUrl = o.optStr("poster_url")
)

private fun parseSeason(o: JSONObject): SeasonInfo {
    val eps = mutableListOf<EpisodeInfo>()
    val arr = o.optJSONArray("episodes")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            eps.add(EpisodeInfo(e.optInt("season"), e.optInt("number"), e.optStr("title")))
        }
    }
    return SeasonInfo(o.optInt("number"), eps)
}

private fun parseDetails(o: JSONObject): MediaDetails {
    val genres = mutableListOf<String>()
    val ga = o.optJSONArray("genres")
    if (ga != null) for (i in 0 until ga.length()) genres.add(ga.getString(i))
    val seasons = mutableListOf<SeasonInfo>()
    val sa = o.optJSONArray("seasons")
    if (sa != null) for (i in 0 until sa.length()) seasons.add(parseSeason(sa.getJSONObject(i)))
    val dubs = mutableListOf<DubInfo>()
    val da = o.optJSONArray("dubs")
    if (da != null) {
        for (i in 0 until da.length()) {
            val d = da.getJSONObject(i)
            dubs.add(
                DubInfo(
                    d.optStr("subject_id") ?: "",
                    d.optStr("language") ?: "",
                    d.optStr("label") ?: ""
                )
            )
        }
    }
    return MediaDetails(
        id = parseMediaId(o.getJSONObject("id")),
        title = o.getString("title"),
        mediaType = o.optStr("media_type") ?: "movie",
        year = o.optStr("year"),
        description = o.optStr("description"),
        imdbRating = o.optStr("imdb_rating"),
        posterUrl = o.optStr("poster_url"),
        duration = o.optStr("duration"),
        genres = genres,
        seasons = seasons,
        dubs = dubs
    )
}

private fun parseMirror(o: JSONObject): Mirror {
    val hs = mutableListOf<Pair<String, String>>()
    val arr = o.optJSONArray("headers")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val pair = arr.optJSONArray(i) ?: continue
            if (pair.length() >= 2) hs.add(pair.getString(0) to pair.getString(1))
        }
    }
    return Mirror(o.optStr("label") ?: "", o.getString("resolver_url"), hs)
}

private fun parseRelease(o: JSONObject): ReleaseInfo {
    val mirrors = mutableListOf<Mirror>()
    val ma = o.optJSONArray("mirrors")
    if (ma != null) for (i in 0 until ma.length()) mirrors.add(parseMirror(ma.getJSONObject(i)))
    return ReleaseInfo(
        filename = o.optStr("filename") ?: "",
        quality = o.optStr("quality"),
        language = o.optStr("language"),
        sizeBytes = o.optLongObj("size_bytes"),
        mirrors = mirrors,
        resourceId = o.optStr("resource_id")
    )
}

object Backend {
    @Volatile private var ready = false

    fun configDir(ctx: Context): File = File(ctx.filesDir, "mb_config")
    fun dataDir(ctx: Context): File = File(ctx.filesDir, "mb_data")
    fun cacheDir(ctx: Context): File = File(ctx.cacheDir, "mb_cache")
    fun downloadDir(ctx: Context): File = File(ctx.filesDir, "mb_downloads")

    /** One-time backend init. Safe to call repeatedly. Throws on failure. */
    suspend fun ensureInit(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        if (ready) return@withContext true
        val spec = JSONObject()
            .put("config_dir", configDir(ctx).absolutePath)
            .put("data_dir", dataDir(ctx).absolutePath)
            .put("cache_dir", cacheDir(ctx).absolutePath)
            .toString()
        envelope(NativeBridge.init(spec))
        ready = true
        true
    }

    private fun envelope(raw: String?): JSONObject {
        if (raw == null) throw BackendException("native bridge returned null")
        val obj = JSONObject(raw)
        if (obj.has("error") && !obj.isNull("error")) {
            throw BackendException(obj.getString("error"))
        }
        return obj
    }

    private fun okObj(raw: String?): JSONObject = envelope(raw).getJSONObject("ok")
    private fun okArr(raw: String?): JSONArray = envelope(raw).getJSONArray("ok")

    suspend fun version(): JSONObject = withContext(Dispatchers.IO) {
        okObj(NativeBridge.version())
    }

    suspend fun search(provider: String, query: String, page: Int): List<CatalogItem> =
        withContext(Dispatchers.IO) {
            val a = okArr(NativeBridge.search(provider, query, page))
            List(a.length()) { parseCatalogItem(a.getJSONObject(it)) }
        }

    suspend fun details(provider: String, id: String): MediaDetails =
        withContext(Dispatchers.IO) {
            parseDetails(okObj(NativeBridge.details(provider, id)))
        }

    suspend fun streams(
        provider: String,
        id: String,
        season: Int,
        episode: Int,
        isSeries: Boolean
    ): List<ReleaseInfo> = withContext(Dispatchers.IO) {
        val a = okArr(NativeBridge.streams(provider, id, season, episode, isSeries))
        List(a.length()) { parseRelease(a.getJSONObject(it)) }
    }

    suspend fun subtitles(
        subjectId: String,
        resourceId: String,
        season: Int,
        episode: Int
    ): List<SubtitleInfo> = withContext(Dispatchers.IO) {
        val a = okArr(NativeBridge.subtitles(subjectId, resourceId, season, episode))
        List(a.length()) {
            val o = a.getJSONObject(it)
            SubtitleInfo(o.getString("name"), o.getString("url"))
        }
    }

    suspend fun homepage(tabId: String, page: Int): List<CatalogItem> =
        withContext(Dispatchers.IO) {
            val items = okObj(NativeBridge.homepage(tabId, page)).getJSONArray("items")
            List(items.length()) { parseCatalogItem(items.getJSONObject(it)) }
        }

    suspend fun suggest(query: String): List<String> = withContext(Dispatchers.IO) {
        val a = okArr(NativeBridge.suggest(query))
        List(a.length()) { a.getString(it) }
    }

    suspend fun m3u(source: String): List<ChannelInfo> = withContext(Dispatchers.IO) {
        val a = okArr(NativeBridge.m3u(source))
        List(a.length()) {
            val o = a.getJSONObject(it)
            ChannelInfo(
                o.optStr("id") ?: "",
                o.optStr("name") ?: "",
                o.optStr("logo") ?: "",
                o.optStr("group") ?: "",
                o.optStr("stream_url") ?: ""
            )
        }
    }

    suspend fun downloadStart(
        url: String,
        destination: String,
        headers: List<Pair<String, String>>
    ): Long = withContext(Dispatchers.IO) {
        val ha = JSONArray()
        for ((k, v) in headers) ha.put(JSONObject().put("name", k).put("value", v))
        val spec = JSONObject()
            .put("url", url)
            .put("destination", destination)
            .put("headers", ha)
            .toString()
        okObj(NativeBridge.downloadStart(spec)).getLong("id")
    }

    data class DlStatus(val downloaded: Long, val total: Long?, val finished: JSONObject?)

    suspend fun downloadStatus(id: Long): DlStatus = withContext(Dispatchers.IO) {
        val o = okObj(NativeBridge.downloadStatus(id))
        DlStatus(
            o.optLong("downloaded"),
            if (o.isNull("total")) null else o.optLong("total"),
            if (o.isNull("finished")) null else o.getJSONObject("finished")
        )
    }

    suspend fun downloadCancel(id: Long): Unit = withContext(Dispatchers.IO) {
        envelope(NativeBridge.downloadCancel(id)); Unit
    }

    suspend fun downloadRelease(id: Long): Unit = withContext(Dispatchers.IO) {
        envelope(NativeBridge.downloadRelease(id)); Unit
    }
}
