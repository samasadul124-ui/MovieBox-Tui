package com.moviebox.core

/**
 * JNI façade over libmoviebox_tui.so.
 *
 * Class/method names must match the Rust exports in src/mobile.rs EXACTLY
 * (Java_com_moviebox_core_NativeBridge_*). Every method returns a JSON
 * string: {"ok": …} or {"error": "…"} (null = JNI-layer failure).
 * All calls block; invoke only from background threads.
 */
object NativeBridge {
    init {
        System.loadLibrary("moviebox_tui")
    }

    @JvmStatic external fun init(specJson: String): String?
    @JvmStatic external fun version(): String?
    @JvmStatic external fun search(provider: String, query: String, page: Int): String?
    @JvmStatic external fun details(provider: String, id: String): String?
    @JvmStatic external fun streams(
        provider: String,
        id: String,
        season: Int,
        episode: Int,
        isSeries: Boolean
    ): String?

    @JvmStatic external fun subtitles(
        subjectId: String,
        resourceId: String,
        season: Int,
        episode: Int
    ): String?

    @JvmStatic external fun homepage(tabId: String, page: Int): String?
    @JvmStatic external fun suggest(query: String): String?
    @JvmStatic external fun m3u(source: String): String?
    @JvmStatic external fun downloadStart(specJson: String): String?
    @JvmStatic external fun downloadStatus(id: Long): String?
    @JvmStatic external fun downloadCancel(id: Long): String?
    @JvmStatic external fun downloadRelease(id: Long): String?
}
