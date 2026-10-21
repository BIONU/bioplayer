package com.bio.bioplayer.parser

import com.bio.bioplayer.model.Group
import com.bio.bioplayer.model.Station

/**
 * Parses extended M3U playlists into the same Group/Station tree the JSON
 * parser produces, so both formats browse identically in the UI.
 *
 * Supports tvg-logo, group-title, group-logo (per reference), plus
 * #EXTVLCOPT http-user-agent / http-referrer and a trailing
 * `|User-Agent=...&Referer=...` on the URL line.
 */
object M3uParser {

    private val ATTR_REGEX = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")

    // ความยาวสูงสุดของชื่อที่แสดงในรายการ
    private const val MAX_DISPLAY_NAME_LENGTH = 40

    /**
     * ตัดชื่อให้สวยงามสำหรับแสดงผล
     */
    private fun formatDisplayName(name: String): String {
        return name.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * ย่อ URL / shortlink ให้สั้นลง
     * ตัวอย่าง:
     *   https://bit.ly/abc123          → bit.ly
     *   https://example.com/playlist   → example.com/playlist
     *   https://cdn.example.com/a/b/c  → cdn.example.com/.../c
     */
    private fun shortenUrl(url: String): String {
        return try {
            val clean = url
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("//")
                .substringBefore("?")
                .substringBefore("#")
                .trimEnd('/')

            val parts = clean.split("/").filter { it.isNotBlank() }
            when {
                parts.isEmpty() -> url
                parts.size == 1 -> parts[0]                          // แค่ domain
                parts.size == 2 -> "${parts[0]}/${parts[1]}"         // domain/path
                else -> "${parts[0]}/.../${parts.last()}"            // domain/.../last
            }
        } catch (e: Exception) {
            url
        }
    }

    /**
     * ทำความสะอาดชื่อไฟล์/URL:
     * - ถ้าเป็น shortlink หรือ URL → แสดง domain หรือ path สั้น ๆ
     * - ถ้าเป็นชื่อไฟล์ .m3u → ใช้ชื่อไฟล์ (ตัดนามสกุล)
     * - นอกนั้นเป็น "My Playlist"
     */
    private fun getCleanFileName(fileName: String, content: String): String {
        val name = fileName.trim()
        if (name.isBlank() ||
            name.equals("Root", true) ||
            name.equals("My Playlist", true)
        ) {
            return "My Playlist"
        }

        // กรณีเป็น URL / shortlink
        if (name.startsWith("http://", ignoreCase = true) ||
            name.startsWith("https://", ignoreCase = true) ||
            name.startsWith("//")
        ) {
            return shortenUrl(name)
        }

        // กรณีเป็นชื่อไฟล์ → ตัด path และนามสกุล .m3u / .m3u8 / .json ออก
        val clean = name
            .substringBefore("?")
            .substringAfterLast("/")
            .replace(Regex("""\.(m3u8?|json)$""", RegexOption.IGNORE_CASE), "")
            .trim()

        return clean.ifBlank { "My Playlist" }
    }

    fun parse(content: String, fileName: String = "My Playlist"): Group {
        val cleanRootName = getCleanFileName(fileName, content)

        // สร้าง Root ไว้ก่อน
        val root = Group(name = cleanRootName)
        val groupsByName = LinkedHashMap<String, Group>()

        var pendingName: String? = null
        var pendingLogo = ""
        var pendingGroupTitle: String? = null
        var pendingGroupLogo = ""
        var pendingUserAgent = ""
        var pendingReferer = ""
        var firstM3uLogo: String? = null

        fun reset() {
            pendingName = null
            pendingLogo = ""
            pendingGroupTitle = null
            pendingGroupLogo = ""
            pendingUserAgent = ""
            pendingReferer = ""
        }

        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#EXTM3U")) {
                continue
            }

            when {
                line.startsWith("#EXTINF:") -> {
                    val attrsPart = line
                        .substringAfter(":")
                        .substringBeforeLast(",", "")

                    val displayName = line
                        .substringAfterLast(",", "")
                        .trim()

                    val attrs = ATTR_REGEX
                        .findAll(attrsPart)
                        .associate {
                            it.groupValues[1].lowercase() to it.groupValues[2]
                        }

                    val rawName = if (displayName.isNotBlank()) {
                        displayName
                    } else {
                        attrs["tvg-name"] ?: "Unnamed"
                    }

                    pendingName = formatDisplayName(rawName)

                    val logo = attrs["tvg-logo"] ?: ""
                    pendingLogo = logo
                    if (firstM3uLogo == null && logo.isNotBlank()) {
                        firstM3uLogo = logo
                    }

                    pendingGroupTitle = attrs["group-title"]?.ifBlank { null }
                    pendingGroupLogo = attrs["group-logo"] ?: ""
                }

                line.startsWith("#EXTGRP:") -> {
                    val groupTitle = line.substringAfter(":").trim()
                    if (groupTitle.isNotEmpty()) {
                        pendingGroupTitle = groupTitle
                    }
                }

                line.startsWith("#EXTVLCOPT:") -> {
                    val opt = line.substringAfter(":")
                    when {
                        opt.startsWith("http-user-agent=", true) -> {
                            pendingUserAgent = opt.substringAfter("=")
                        }
                        opt.startsWith("http-referrer=", true) ||
                        opt.startsWith("http-referer=", true) -> {
                            pendingReferer = opt.substringAfter("=")
                        }
                    }
                }

                line.startsWith("#") -> {
                    // ignore unknown directive
                }

                else -> {
                    var url = line
                    var ua = pendingUserAgent
                    var ref = pendingReferer

                    if (url.contains("|")) {
                        val parts = url.split("|", limit = 2)
                        url = parts[0]
                        parts.getOrNull(1)?.split("&")?.forEach { kv ->
                            val idx = kv.indexOf("=")
                            if (idx > 0) {
                                val k = kv.substring(0, idx)
                                val v = kv.substring(idx + 1)
                                when (k.lowercase()) {
                                    "user-agent" -> ua = v
                                    "referer", "referrer" -> ref = v
                                }
                            }
                        }
                    }

                    val station = Station(
                        name = pendingName ?: "Unnamed",
                        url = url,
                        image = pendingLogo,
                        referer = ref,
                        userAgent = ua
                    )

                    val gTitle = pendingGroupTitle
                    if (gTitle != null) {
                        val group = groupsByName.getOrPut(gTitle) {
                            Group(
                                name = gTitle,
                                image = pendingGroupLogo
                            ).also {
                                root.groups.add(it)
                            }
                        }
                        group.stations.add(station)
                    } else {
                        root.stations.add(station)
                    }

                    reset()
                }
            }
        }

        val finalRoot = if (!firstM3uLogo.isNullOrBlank()) {
            root.copy(image = firstM3uLogo)
        } else {
            root
        }

        return finalRoot
    }
}
