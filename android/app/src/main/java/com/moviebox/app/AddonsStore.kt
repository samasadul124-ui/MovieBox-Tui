package com.moviebox.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads/writes the SAME addons_config.json the Rust backend uses
 * (see config::load_addons / InstalledAddon). Field names must match the
 * Rust serde fields exactly. The backend auto-repairs a missing core
 * (Cinemeta) addon on load, so this store never needs to.
 */
data class InstalledAddon(
    var manifestUrl: String,
    var name: String,
    var enabled: Boolean,
    var providesCatalog: Boolean,
    var providesMeta: Boolean,
    var providesStream: Boolean
)

object AddonsStore {
    private fun file(ctx: Context): File = File(Backend.configDir(ctx), "addons_config.json")

    fun load(ctx: Context): MutableList<InstalledAddon> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        val out = mutableListOf<InstalledAddon>()
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    InstalledAddon(
                        manifestUrl = o.optString("manifest_url", ""),
                        name = o.optString("name", ""),
                        enabled = o.optBoolean("enabled", true),
                        providesCatalog = o.optBoolean("provides_catalog", false),
                        providesMeta = o.optBoolean("provides_meta", false),
                        providesStream = o.optBoolean("provides_stream", false)
                    )
                )
            }
        } catch (_: Exception) {
            // Corrupt file: Rust side rotates it on next load; start empty.
        }
        return out
    }

    fun save(ctx: Context, list: List<InstalledAddon>) {
        val dir = Backend.configDir(ctx)
        if (!dir.exists()) dir.mkdirs()
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject()
                    .put("manifest_url", a.manifestUrl)
                    .put("name", a.name.ifEmpty { a.manifestUrl })
                    .put("version", JSONObject.NULL)
                    .put("description", JSONObject.NULL)
                    .put("enabled", a.enabled)
                    .put("provides_catalog", a.providesCatalog)
                    .put("provides_meta", a.providesMeta)
                    .put("provides_stream", a.providesStream)
                    .put("id_prefixes", JSONArray())
                    .put("types", JSONArray())
            )
        }
        file(ctx).writeText(arr.toString(2))
    }
}
