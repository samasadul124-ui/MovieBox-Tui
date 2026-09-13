package com.moviebox.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder

/**
 * Opens streams in the external VLC app (org.videolan.vlc).
 *
 * VLC accepts no custom headers via intent, so CloudFront cookie auth is
 * baked into the URL as a signed query string (Policy/Signature/Key-Pair-Id),
 * which CloudFront honors identically to cookies (verified: 200 with zero
 * headers; Referer/UA proved unnecessary).
 */
object VlcPlayer {
    const val VLC_PACKAGE = "org.videolan.vlc"

    /** Bake CloudFront signed-cookie values into a signed query-string URL. */
    fun withQueryAuth(url: String, headers: List<Pair<String, String>>): String {
        if ("Policy=" in url && "Signature=" in url) return url
        var policy: String? = null
        var sig: String? = null
        var kid: String? = null
        for ((k, v) in headers) {
            if (!k.equals("Cookie", ignoreCase = true)) continue
            for (part in v.split(";")) {
                val name = part.substringBefore("=").trim()
                val value = part.substringAfter("=", "").trim()
                when (name) {
                    "CloudFront-Policy" -> policy = value
                    "CloudFront-Signature" -> sig = value
                    "CloudFront-Key-Pair-Id" -> kid = value
                }
            }
        }
        if (policy.isNullOrEmpty() || sig.isNullOrEmpty() || kid.isNullOrEmpty()) {
            return url
        }
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
        val sep = if ("?" in url) "&" else "?"
        return url + sep + "Policy=${enc(policy)}&Signature=${enc(sig)}&Key-Pair-Id=${enc(kid)}"
    }

    private fun mimeFor(url: String): String {
        val path = url.substringBefore("?").lowercase()
        return when {
            ".mpd" in path -> "application/dash+xml"
            ".m3u8" in path -> "application/x-mpegURL"
            else -> "video/*"
        }
    }

    /** Returns false when VLC is not installed (Play Store opened instead). */
    fun open(
        ctx: Context,
        url: String,
        headers: List<Pair<String, String>>,
        title: String
    ): Boolean {
        val authed = withQueryAuth(url, headers)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(authed), mimeFor(authed))
            setPackage(VLC_PACKAGE)
            putExtra("title", title)
        }
        try {
            ctx.startActivity(view)
            return true
        } catch (_: ActivityNotFoundException) {
        }
        Toast.makeText(ctx, "VLC not installed — opening Play Store…", Toast.LENGTH_LONG).show()
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$VLC_PACKAGE"))
            )
        } catch (_: Exception) {
            try {
                ctx.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$VLC_PACKAGE")
                    )
                )
            } catch (_: Exception) {
            }
        }
        return false
    }
}
