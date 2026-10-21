package com.bio.bioplayer.parser

import com.bio.bioplayer.model.Group
import com.bio.bioplayer.model.Station
import org.json.JSONArray
import org.json.JSONObject

/**
 * รองรับฟอร์แมต "provider" อีกแบบที่นิยมใช้กัน เช่นไฟล์ config ของแอป IPTV ทั่วไป:
 *
 * {
 *   "provider": { "user_message": "TV_TH", ... },
 *   "categories": { "ทีวีไทย": { "name": "...", "icon": "..." }, ... },
 *   "channels": [ { "category": "ทีวีไทย", "name": "NBTHD", "icon": "...",
 *                   "url": "...m3u8", "referer": "...", "user_agent": "..." }, ... ]
 * }
 *
 * แปลงเป็นโครงสร้าง Group/Station เดิมของ BIOplayer: 1 category = 1 group,
 * 1 channel = 1 station โดยคงลำดับช่องตามไฟล์ต้นฉบับ
 *
 * จุดที่ต้องทน:
 *  - บางช่องใส่ URL ผิด key (เป็น "" แทน "url") — ใช้ค่าจาก "" เป็น fallback
 *  - ช่องที่ไม่มี URL เลยจะถูกข้าม (เล่นไม่ได้อยู่แล้ว)
 *  - ฟิลด์ drm_type/drm_key ยังไม่ถูกนำไปใช้ (แอปยังไม่มีระบบ DRM) — ช่องพวกนั้น
 *    ยังแสดงตามปกติ แต่ถ้าสตรีมบังคับ DRM จะเล่นไม่ได้
 */
object ProviderFormatParser {

    fun parse(o: JSONObject): Group {
        val provider = o.optJSONObject("provider")
        val categories = o.optJSONObject("categories")
        val channels: JSONArray = o.optJSONArray("channels") ?: JSONArray()

        // LinkedHashMap เพื่อคงลำดับ category ตามที่เจอครั้งแรกใน channels
        val groups = LinkedHashMap<String, Group>()

        for (i in 0 until channels.length()) {
            val c = channels.optJSONObject(i) ?: continue
            val catKey = c.optString("category").ifBlank { "อื่น ๆ" }
            val group = groups.getOrPut(catKey) {
                val meta = categories?.optJSONObject(catKey)
                Group(
                    name = meta?.optString("name")?.takeIf { it.isNotBlank() } ?: catKey,
                    image = meta?.optString("icon") ?: ""
                )
            }

            val name = c.optString("name").trim()
            var url = c.optString("url").trim()
            if (url.isBlank()) url = c.optString("").trim() // URL ใส่ผิด key ("")
            if (name.isBlank() || url.isBlank()) continue // ไม่มีชื่อ/ไม่มี URL → ข้าม

            group.stations.add(
                Station(
                    name = name,
                    url = url,
                    image = c.optString("icon"),
                    referer = c.optString("referer"),
                    userAgent = c.optString("user_agent", c.optString("userAgent")),
                    embed = c.optBoolean("embed", false),
                    desktop = c.optBoolean("desktop", false),
                    isHost = c.optBoolean("isHost", c.optBoolean("host", false))
                )
            )
        }

        return Group(
            name = provider?.optString("user_message")?.takeIf { it.isNotBlank() }
                ?: o.optString("name").takeIf { it.isNotBlank() }
                ?: "Playlist",
            author = "",
            groups = groups.values.toMutableList()
        )
    }
}
