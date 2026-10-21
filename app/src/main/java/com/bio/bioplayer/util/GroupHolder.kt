package com.bio.bioplayer.util

import com.bio.bioplayer.model.Group
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** เก็บ Group ที่ใหญ่เกินจะส่งผ่าน Intent (จำกัด ~1MB) ไว้ในหน่วยความจำ ส่งแค่ key แทน */
object GroupHolder {
    private val map = ConcurrentHashMap<String, Group>()

    fun put(group: Group): String {
        val key = UUID.randomUUID().toString()
        map[key] = group
        return key
    }

    fun get(key: String): Group? = map[key]

    fun remove(key: String) {
        map.remove(key)
    }
}
