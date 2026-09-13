package com.moviebox.app

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import java.net.URLEncoder

/**
 * Opens streams in the external VLC app (org.videolan.vlc).
 *
 * VLC accepts no custom headers via intent, so CloudFront cookie auth is
 * baked into the URL as a signed query string (Policy/Signature/Key-Pair-Id),
 * which the CDN honors identically to cookies (verified: HTTP 200 with zero
 * headers on the current CDN host).
 *
 * Handoff strategy (v6): implicit VIEW with a VLC-friendly MIME first, then
 * an explicit retry against VLC's entrypoint with data only (VLC sniffs the
 * content itself). The outcome is diagnosed HONESTLY: "VLC missing" and
 * "VLC rejected the stream" are different failures with different toasts.
 */
object VlcPlayer {
    const val VLC_PACKAGE = "org.videolan.vlc"
    private const val VLC_ENTRY = "org.videolan.vlc.StartActivity"

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
        // NOTE: MPD goes out as video/*, not application/dash+xml: VLC sniffs
        // content itself, and video/* matches its player filter on every
        // version (a too-specific MIME risks ActivityNotFoundException even
        // with VLC installed).
        return when {
            ".m3u8" in path -> "application/x-mpegURL"
            else -> "video/*"
        }
    }

    /** True when the VLC package is installed (needs the manifest queries block). */
    fun isInstalled(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.packageManager.getPackageInfo(
                    VLC_PACKAGE, PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(VLC_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun openPlayStore(ctx: Context) {
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
    }

    /**
     * Returns false when the handoff failed (caller falls back to in-app).
     * Toasts the HONEST reason: missing VLC vs rejected stream.
     */
    fun open(
        ctx: Context,
        url: String,
        headers: List<Pair<String, String>>,
        title: String
    ): Boolean {
        val authed = withQueryAuth(url, headers)
        val uri = Uri.parse(authed)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(authed))
            setPackage(VLC_PACKAGE)
            putExtra("title", title)
        }
        try {
            ctx.startActivity(view)
            return true
        } catch (_: ActivityNotFoundException) {
        }
        val explicit = Intent(Intent.ACTION_VIEW).apply {
            setData(uri)
            component = ComponentName(VLC_PACKAGE, VLC_ENTRY)
            putExtra("title", title)
        }
        try {
            ctx.startActivity(explicit)
            return true
        } catch (_: Exception) {
        }
        if (isInstalled(ctx)) {
            Toast.makeText(
                ctx,
                "VLC couldn't open this stream — playing in-app instead",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(ctx, "VLC not installed — opening Play Store…", Toast.LENGTH_LONG).show()
            openPlayStore(ctx)
        }
        return false
    }
}
