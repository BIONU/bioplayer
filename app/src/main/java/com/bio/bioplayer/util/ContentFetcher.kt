package com.bio.bioplayer.util

import android.content.ContentResolver
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request

object ContentFetcher {

    private val http = OkHttpClient()

    fun fetchUrl(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("Empty response")
        }
    }

    fun fetchUri(resolver: ContentResolver, uri: Uri): String {
        return resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("Could not open file")
    }
}
