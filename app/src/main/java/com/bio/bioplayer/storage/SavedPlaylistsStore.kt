package com.bio.bioplayer.storage

import android.content.Context
import com.bio.bioplayer.model.SavedPlaylist
import com.bio.bioplayer.model.SourceType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Stores only the lightweight playlist entries shown on the "Lists" screen
 * (name, author, image, source). The actual groups/stations are re-fetched
 * and re-parsed from `source` each time the user opens a playlist.
 */
class SavedPlaylistsStore(context: Context) {

    private val prefs = context.getSharedPreferences("bioplayer_playlists", Context.MODE_PRIVATE)
    private val key = "saved_playlists_json"

    fun getAll(): List<SavedPlaylist> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val out = mutableListOf<SavedPlaylist>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                SavedPlaylist(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    author = o.optString("author"),
                    image = o.optString("image"),
                    sourceType = if (o.optString("sourceType") == "FILE") SourceType.FILE else SourceType.URL,
                    source = o.optString("source")
                )
            )
        }
        return out
    }

    fun add(name: String, author: String, image: String, sourceType: SourceType, source: String) {
        val list = getAll().toMutableList()
        list.add(
            SavedPlaylist(
                id = UUID.randomUUID().toString(),
                name = name,
                author = author,
                image = image,
                sourceType = sourceType,
                source = source
            )
        )
        save(list)
    }

    fun remove(id: String) {
        save(getAll().filterNot { it.id == id })
    }

    // เปลี่ยนชื่อโดยคงลำดับเดิมและ id เดิมไว้
    fun rename(id: String, newName: String) {
        save(getAll().map { if (it.id == id) it.copy(name = newName) else it })
    }

    private fun save(list: List<SavedPlaylist>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("author", p.author)
                    put("image", p.image)
                    put("sourceType", p.sourceType.name)
                    put("source", p.source)
                }
            )
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
