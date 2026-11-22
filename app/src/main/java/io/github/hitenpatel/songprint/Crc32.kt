package io.github.hitenpatel.songprint

private val CRC32_TABLE = IntArray(256) { i ->
    var crc = i
    repeat(8) {
        crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
    }
    crc
}

/** Pure-Kotlin CRC32 — no java.util.zip dependency, compiles on all KMP targets. */
internal fun crc32(data: ByteArray, offset: Int, length: Int): Long {
    var crc = 0xFFFFFFFF.toInt()
    for (i in offset until offset + length) {
        crc = (crc ushr 8) xor CRC32_TABLE[(crc xor data[i].toInt()) and 0xFF]
    }
    return (crc xor 0xFFFFFFFF.toInt()).toLong() and 0xFFFFFFFFL
}
