package com.bio.bioplayer.parser

import com.bio.bioplayer.model.Group
import com.bio.bioplayer.model.Station
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses nested Wiseplay-style JSON playlists, mirroring the reference
 * `_parse_group` recursion: a group object may have its own "stations"
 * array (leaf channels) and/or its own "groups" array (sub-groups), to
 * any depth. A bare top-level array is wrapped under a synthetic "Root"
 * group, same as the reference loader.
 *
 * รองรับเพิ่ม: ฟอร์แมต "provider" (provider/categories/channels) —
 * ดู ProviderFormatParser — และถ้าเจอไฟล์ JSON ที่เขียนหลวม ๆ
 * (เลขนำหน้า 0, ลืมปิด quote) จะลองซ่อมให้อัตโนมัติผ่าน JsonRepair แล้ว parse ใหม่
 */
object JsonPlaylistParser {

    fun parse(content: String): Group {
        val trimmed = content.trim()
        return when {
            trimmed.startsWith("[") -> {
                val root = Group(name = "Root")
                val array = parseLenient(trimmed) as JSONArray
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { parseGroup(it)?.let(root.groups::add) }
                }
                root
            }
            trimmed.startsWith("{") -> {
                val obj = parseLenient(trimmed) as JSONObject
                if (obj.optJSONArray("channels") != null) {
                    ProviderFormatParser.parse(obj)
                } else {
                    parseGroup(obj) ?: Group(name = "Root")
                }
            }
            else -> throw IllegalArgumentException("Content is not JSON")
        }
    }

    /** parse ตามปกติ ถ้าพังเพราะ JSON หลวม ๆ ให้ลองซ่อม (JsonRepair) แล้ว parse ใหม่อีกรอบ */
    private fun parseLenient(trimmed: String): Any {
        val isArray = trimmed.startsWith("[")
        return try {
            if (isArray) JSONArray(trimmed) else JSONObject(trimmed)
        } catch (original: JSONException) {
            try {
                val repaired = JsonRepair.repair(trimmed)
                if (isArray) JSONArray(repaired) else JSONObject(repaired)
            } catch (_: JSONException) {
                throw original // โยน error ต้นฉบับให้ PlaylistParser ไป fallback เป็น M3U ตามเดิม
            }
        }
    }

    private fun parseGroup(o: JSONObject): Group? {
        val stations = mutableListOf<Station>()
        o.optJSONArray("stations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val headers = mutableMapOf<String, String>()
                s.optJSONObject("headers")?.let { h ->
                    h.keys().forEach { k -> headers[k] = h.optString(k, "") }
                }
                stations.add(
                    Station(
                        name = s.optString("name", ""),
                        url = s.optString("url", ""),
                        image = s.optString("image", ""),
                        referer = s.optString("referer", ""),
                        // ไฟล์ตัวอย่างใช้ "user_agent" (snake_case) ไม่ใช่ "userAgent" — รับได้ทั้งคู่
                        userAgent = s.optString("userAgent", s.optString("user_agent", "")),
                        isHost = s.optBoolean("isHost", s.optBoolean("host", false)),
                        embed = s.optBoolean("embed", false),
                        desktop = s.optBoolean("desktop", false),
                        headers = headers
                    )
                )
            }
        }

        val groups = mutableListOf<Group>()
        o.optJSONArray("groups")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                parseGroup(g)?.let(groups::add)
            }
        }

        return Group(
            name = o.optString("name", ""),
            image = o.optString("image", ""),
            url = o.optString("url", ""),
            author = o.optString("author", ""),
            groups = groups,
            stations = stations
        )
    }
}
