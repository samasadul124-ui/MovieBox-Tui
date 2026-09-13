# Android APK backend

This crate doubles as the backend of a native Android app: with the `mobile`
Cargo feature it compiles to `libmoviebox_tui.so` (a `cdylib`) exposing the
**identical provider stack as the desktop TUI** (MovieBox, 4KHDHub, BDIX
CircleFTP/DhakaFlix, Stremio addons, M3U TV, download engine, caches) through
a JSON-over-JNI bridge. No provider logic is forked or rewritten: the APK and
the TUI call the same `MovieBoxService` methods.

## Feature matrix

| Feature set | Command | Output |
|---|---|---|
| `app` (default) | `cargo build` | desktop `moviebox-tui` binary (TUI + updater) |
| `mobile` only | `cargo ndk … build --no-default-features --features mobile` | `libmoviebox_tui.so` (no TUI, no updater) |
| `app` + `mobile` | `cargo build --features mobile` | desktop binary *and* bridge (host-side testing) |

The terminal UI (`tui/`, `ratatui`, `crossterm`, `ratatui-image`) and the
self-updater (`updater/`) are compiled out of mobile builds. Backend helpers
that used to live in `tui::text` now live in `util::text` (`tui::text`
re-exports them, so desktop code is untouched).

## Building

Prerequisites: Rust 1.90.0, Android NDK r27+, `cargo-ndk 4.x`.

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r27d
cargo install cargo-ndk --version 4.1.2
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -P 24 \
  -o ./jniLibs \
  build --release --no-default-features --features mobile --locked
```

Notes:

- `-P 24` (API level, *capital* P) matches the crate's Android floor
  (`minSdk 24`). cargo-ndk 4.x uses `-P/--platform`; the old 3.x `-p` flag
  now means something else and will fail.
- `-o` writes an `app/src/main/jniLibs/<abi>/libmoviebox_tui.so` tree
  directly consumable by Android Gradle Plugin.
- A ready-to-run Google Colab notebook that performs this exact build
  (toolchain + NDK install, compile, verification, packaging) is maintained
  alongside this repo — see the project workspace, not this directory.
- 64-bit ABIs link with 16 KB `LOAD` alignment (Android 15 requirement);
  `armeabi-v7a` correctly uses 4 KB alignment (32-bit ARM has no 16 KB
  pages) — see Verification below.

## JNI contract

Kotlin class: `com.moviebox.core.NativeBridge` (all methods `static` /
`@JvmStatic external`). Load with `System.loadLibrary("moviebox_tui")`.

Every method returns a JSON string:

- success: `{"ok": <value>}`
- failure: `{"error": "<message>"}`
- `null`: the JNI layer itself failed (out of memory) — retry or abort
  that call; the backend stays usable.

| # | Kotlin signature | Backend call | Returns (`ok`) |
|---|---|---|---|
| 1 | `init(specJson: String): String?` | `core_init` | `{"first_init": bool, "runtime": bool}` |
| 2 | `version(): String?` | `core_version` | `{"version": "0.1.18", "flavor": "mobile", "api": 1}` |
| 3 | `search(provider: String, query: String, page: Int): String?` | `core_search` | `[CatalogItem…]` |
| 4 | `details(provider: String, id: String): String?` | `core_details` | `MediaDetails` |
| 5 | `streams(provider: String, id: String, season: Int, episode: Int, isSeries: Boolean): String?` | `core_streams` | `[Release…]` (URLs + headers + mirrors) |
| 6 | `subtitles(subjectId: String, resourceId: String, season: Int, episode: Int): String?` | `core_subtitles` | `[SubtitleOption…]` |
| 7 | `homepage(tabId: String, page: Int): String?` | `core_homepage` | `{"items": […], "metrics": {…}}` |
| 8 | `suggest(query: String): String?` | `core_suggest` | `[String…]` |
| 9 | `m3u(source: String): String?` | `core_m3u` | `[Channel…]` |
| 10 | `downloadStart(specJson: String): String?` | `core_download_start` | `{"id": 7}` |
| 11 | `downloadStatus(id: Long): String?` | `core_download_status` | `{"downloaded": n, "total": m\|null, "finished": null\|{…}}` |
| 12 | `downloadCancel(id: Long): String?` | `core_download_cancel` | `true` |
| 13 | `downloadRelease(id: Long): String?` | `core_download_release` | `true` |

Details:

- `provider`: `"moviebox" | "fourkhdhub" | "bdix_circleftp" |
  "bdix_dhakaflix" | "addons"`.
- Search/homepage pages are **1-based** (page 0 is clamped to 1).
- Movies use `season = 0, episode = 0`.
- `init` spec: `{"config_dir": "…/files/mb_config", "data_dir":
  "…/files/mb_data", "cache_dir": "…/cache/mb_cache"}`. Use app-private
  dirs (`context.filesDir`, `context.cacheDir`). First call wins.
- `downloadStart` spec: `{"url": "https://…", "destination":
  "/abs/app-private/file.mp4", "headers": [{"name": "Referer", "value":
  "…"}]}`. Only `http(s)` URLs. Poll `downloadStatus` (~3 Hz) until
  `finished` is set (`status`: `completed | cancelled | failed`), then
  call `downloadRelease` to free the slot and export the file through
  `MediaStore` from Kotlin (the backend must never write to shared
  storage directly — scoped storage).
- `m3u` accepts an `http(s)` URL (fetched + 8 MiB cap) or raw playlist
  text (Kotlin reads local files itself).

## Threading rules (read before writing Kotlin)

1. Call every bridge method from a **background thread**
   (`Dispatchers.IO`). Calls block until the backend future completes;
   calling from the UI thread ANRs the app.
2. The methods are thread-safe and re-entrant; downloads run on the
   backend Tokio runtime independently of the calling thread.
3. Keep downloads alive with a Kotlin `ForegroundService` — if Android
   kills the process mid-download, the engine's `.part` sidecars make the
   next `downloadStart` to the same destination resume.

## Reference Kotlin façade (copy-paste starter)

```kotlin
package com.moviebox.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object NativeBridge {
    init {
        System.loadLibrary("moviebox_tui")
    }

    @JvmStatic external fun init(specJson: String): String?
    @JvmStatic external fun version(): String?
    @JvmStatic external fun search(provider: String, query: String, page: Int): String?
    @JvmStatic external fun details(provider: String, id: String): String?
    @JvmStatic external fun streams(
        provider: String, id: String, season: Int, episode: Int, isSeries: Boolean
    ): String?
    @JvmStatic external fun subtitles(
        subjectId: String, resourceId: String, season: Int, episode: Int
    ): String?
    @JvmStatic external fun homepage(tabId: String, page: Int): String?
    @JvmStatic external fun suggest(query: String): String?
    @JvmStatic external fun m3u(source: String): String?
    @JvmStatic external fun downloadStart(specJson: String): String?
    @JvmStatic external fun downloadStatus(id: Long): String?
    @JvmStatic external fun downloadCancel(id: Long): String?
    @JvmStatic external fun downloadRelease(id: Long): String?
}

class BackendException(message: String) : Exception(message)

/** Parse an {"ok"/"error"} envelope; throws [BackendException] on error. */
fun parseOk(raw: String?): Any {
    requireNotNull(raw) { "native bridge returned null" }
    val obj = JSONObject(raw)
    if (obj.has("error")) throw BackendException(obj.getString("error"))
    return obj.get("ok")
}

suspend fun initBackend(context: Context): Boolean = withContext(Dispatchers.IO) {
    val spec = JSONObject()
        .put("config_dir", File(context.filesDir, "mb_config").absolutePath)
        .put("data_dir", File(context.filesDir, "mb_data").absolutePath)
        .put("cache_dir", File(context.cacheDir, "mb_cache").absolutePath)
        .toString()
    (parseOk(NativeBridge.init(spec)) as JSONObject).getBoolean("first_init")
}

suspend fun searchTitles(
    provider: String, query: String, page: Int = 1
): String = withContext(Dispatchers.IO) {
    // Returns the raw "ok" JSON array; map to data classes with your
    // preferred JSON library (field names match providers/models.rs).
    parseOk(NativeBridge.search(provider, query, page)).toString()
}
```

## Playback

Feed `Release.mirrors[0]` (`resolver_url` + `headers`) straight into
Media3 ExoPlayer with a header-injecting `DataSource.Factory`. DASH
(`.mpd`) and HLS are first-class in ExoPlayer; MP4 mirrors play as
progressive streams. Prefer `.srt`/`.vtt` subtitles (ExoPlayer-native).
The desktop-only VLC loopback relay is compiled out of mobile builds —
it exists only because desktop VLC cannot take headers on its CLI.

## Verification (after building)

```bash
NM=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm
RE=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf
for abi in arm64-v8a armeabi-v7a x86_64; do
  so=jniLibs/$abi/libmoviebox_tui.so
  file $so                                            # ELF for Android 24
  test "$($NM -D --defined-only $so | grep -c Java_com_moviebox_core_NativeBridge)" = 13
  $RE -d $so | grep NEEDED                            # only liblog/libdl/libm/libc
  $RE -lW $so | grep LOAD                             # 0x4000 on 64-bit, 0x1000 on v7a
done
```

## Android gotchas (handled / to handle in the app)

- **Cleartext HTTP**: some BDIX mirrors serve `http://`. Android 9+
  blocks cleartext by default — add a `networkSecurityConfig` permitting
  cleartext *only* for those hosts; never set global
  `usesCleartextTraffic="true"`.
- **Permissions**: `INTERNET` + `ACCESS_NETWORK_STATE` always;
  `FOREGROUND_SERVICE` + `POST_NOTIFICATIONS` for downloads.
- **No self-update**: the in-app updater is compiled out. Ship updates as
  new APKs (GitHub Releases / F-Droid); a check-only updater in Kotlin
  can download the APK and fire the installer intent.
- Distribution note: streaming-aggregator apps of this kind are not
  eligible for Google Play; see project docs for the release story.
