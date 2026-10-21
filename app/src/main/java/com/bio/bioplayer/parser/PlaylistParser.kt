package com.bio.bioplayer.parser

import com.bio.bioplayer.model.Group

/** ไฟล์ต้นทางเป็นฟอร์แมตไหน ใช้แยกพฤติกรรมบางอย่างที่ต้องต่างกันระหว่าง 2 ฟอร์แมต
 *  (เช่นลิงก์ youtube ใน m3u ต้องแปลงเป็น /watch แต่ json ให้คงเป็น /embed ตามเดิม) */
enum class PlaylistFormat { JSON, M3U }

object PlaylistParser {
    // เปลี่ยนค่าเริ่มต้นเป็นค่าว่าง ("") เพื่อไม่ให้ไปทับค่าที่ Parser จะใช้ Regex ตรวจสอบ
    fun parse(rawContent: String, fileName: String = ""): Group = parseDetailed(rawContent, fileName).first

    fun parseDetailed(rawContent: String, fileName: String = ""): Pair<Group, PlaylistFormat> {
        val content = rawContent.removePrefix("\uFEFF")
        val trimmed = content.trim()
        return if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            try {
                JsonPlaylistParser.parse(trimmed) to PlaylistFormat.JSON
            } catch (e: Exception) {
                M3uParser.parse(content, fileName) to PlaylistFormat.M3U
            }
        } else {
            M3uParser.parse(content, fileName) to PlaylistFormat.M3U
        }
    }
}
