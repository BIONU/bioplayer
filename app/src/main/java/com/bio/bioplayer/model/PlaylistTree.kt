package com.bio.bioplayer.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** A single playable stream leaf, e.g. one channel/station entry. */
@Parcelize
data class Station(
    val name: String,
    val url: String,
    val image: String = "",
    val referer: String = "",
    val userAgent: String = "",
    val isHost: Boolean = false,
    val embed: Boolean = false,
    val desktop: Boolean = false,
    val headers: Map<String, String> = emptyMap()
) : Parcelable

/**
 * A node in the playlist tree. Mirrors the nested JSON structure directly:
 * a Group may hold further sub-groups (`groups`) and/or leaf stations
 * (`stations`) at the same time, to any depth. The parsed root Group also
 * doubles as the playlist's own metadata (name/image/author) for the
 * "saved playlists" list screen.
 */
@Parcelize
data class Group(
    val name: String = "",
    val image: String = "",
    val url: String = "",
    val author: String = "",
    val groups: MutableList<Group> = mutableListOf(),
    val stations: MutableList<Station> = mutableListOf()
) : Parcelable

/** One entry the user has added, kept in SharedPreferences (not the full content). */
data class SavedPlaylist(
    val id: String,
    val name: String,
    val author: String,
    val image: String,
    val sourceType: SourceType,
    val source: String // URL string, or a content:// URI string for local files
)

enum class SourceType { URL, FILE }
