package com.bio.bioplayer.parser

/**
 * ซ่อม JSON ที่เขียนหลวม ๆ ซึ่งพบในไฟล์ playlist จริง (ทำเป็นบรรทัด ๆ เพื่อไม่ไปแตะค่าใน string):
 *  1) ค่าตัวเลขที่นำหน้าด้วย 0 เช่น "number": 02 — JSON มาตรฐานห้าม
 *  2) ค่า string ที่ลืมปิด quote เช่น "url": "https://...m3u8, (ไม่มี " ปิดท้ายบรรทัด)
 *
 * ใช้เฉพาะกรณีที่ parse ปกติแล้วพังเท่านั้น ไฟล์ที่ valid อยู่แล้วจะไม่ถูกแตะ
 * (การเรียกใช้อยู่ใน JsonPlaylistParser เฉพาะ branch ที่ catch JSONException)
 */
internal object JsonRepair {

    // บรรทัดที่เป็น "key": 01234... (ค่าเป็นตัวเลข) — ตัดเลข 0 ด้านหน้าออก
    private val NUMBER_VALUE = Regex("""^(\s*"[^"]+"?\s*:\s*)0+(\d.*)$""")
    // บรรทัดที่เริ่มด้วย "key": "value ที่ยังไม่มี quote ปิดท้าย
    private val OPEN_STRING = Regex("""^(\s*"[^"]+"?\s*:\s*")(.*)$""")
    private val CLOSED_TAIL = Regex(""""(\s*,)?\s*$""")

    fun repair(content: String): String =
        content.lineSequence().joinToString("\n") { line ->
            var l = NUMBER_VALUE.replace(line) { m ->
                m.groupValues[1] + m.groupValues[2]
            }
            val m = OPEN_STRING.matchEntire(l)
            if (m != null) {
                val value = m.groupValues[2]
                when {
                    CLOSED_TAIL.containsMatchIn(value) -> Unit // ปิด quote แล้ว ไม่แตะ
                    value.endsWith(",") -> l = m.groupValues[1] + value.dropLast(1) + "\","
                    else -> l = m.groupValues[1] + value + "\","
                }
            }
            l
        }
}
