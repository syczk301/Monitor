package com.monitor.intelligentflow

import com.monitor.intelligentflow.ui.components.readMjpegFrame
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.BufferedInputStream
import java.io.EOFException

class MjpegStreamTest {
    private fun input(s: String) = BufferedInputStream(ByteArrayInputStream(s.toByteArray()))
    @Test fun readsMultipartFramesWithoutWaitingForTheNextFrame() {
        val stream = input("--frame\r\nContent-Length: 3\r\n\r\nabc\r\n--frame\r\ncontent-length: 2\r\n\r\nde\r\n--frame--\r\n")
        assertArrayEquals("abc".toByteArray(), readMjpegFrame(stream))
        assertArrayEquals("de".toByteArray(), readMjpegFrame(stream))
        assertNull(readMjpegFrame(stream))
    }
    @Test(expected = EOFException::class) fun rejectsTruncatedFrame() { readMjpegFrame(input("--frame\r\nContent-Length: 3\r\n\r\na")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizeBeforeAllocation() { readMjpegFrame(input("--frame\r\nContent-Length: 3000000\r\n\r\n")) }
    @Test fun preservesFullSizeJpegBytes() {
        val original = ByteArray(256 * 1024) { (it % 251).toByte() }
        val header = "--frame\r\nContent-Length: ${original.size}\r\n\r\n".toByteArray()
        val stream = BufferedInputStream(ByteArrayInputStream(header + original))
        assertArrayEquals(original, readMjpegFrame(stream))
    }
}
