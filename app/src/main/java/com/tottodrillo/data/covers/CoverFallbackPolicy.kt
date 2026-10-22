package com.tottodrillo.data.covers

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Mirrors share a catalogue; the ROM source is an independent fallback. */
object CoverFallbackPolicy {
    fun mirror(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val parts = parsed.pathSegments
        if (parsed.host != "raw.githubusercontent.com" || parts.size != 5 ||
            parts[0] != "libretro-thumbnails" || parts[3] != "Named_Boxarts") return null
        return "https://thumbnails.libretro.com/".toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment(parts[1].replace('_', ' '))
            .addPathSegment("Named_Boxarts").addPathSegment(parts[4]).build().toString()
    }

    fun source(url: String?): String? = url?.toHttpUrlOrNull()
        ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
        ?.toString()
}
