package com.monitor.intelligentflow.ui.components

import java.io.BufferedInputStream
import java.io.EOFException

internal fun readMjpegFrame(input: BufferedInputStream): ByteArray? {
    var line: String
    do { line = readAsciiLine(input) ?: return null } while (!line.startsWith("--"))
    if (line.endsWith("--")) return null
    var length = -1
    while (true) {
        line = readAsciiLine(input) ?: throw EOFException("视频帧头意外中断")
        if (line.isEmpty()) break
        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: -1
    }
    require(length in 1..2*1024*1024) { "无效的视频帧长度: $length" }
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = input.read(bytes, offset, length-offset)
        if (read < 0) throw EOFException("视频流意外中断")
        offset += read
    }
    return bytes
}

private fun readAsciiLine(input: BufferedInputStream): String? {
    val line = StringBuilder(64)
    while (true) {
        val value = input.read()
        if (value < 0) return if (line.isEmpty()) null else line.toString()
        if (value == '\n'.code) return line.toString().trimEnd('\r')
        require(line.length < 4096) { "视频流响应头过长" }
        line.append(value.toChar())
    }
}
