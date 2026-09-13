package io.github.proify.lyricon.xinghe.xposed

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 给 `okhttp3.ResponseBody#byteStream()` 返回的流套一层。
 *
 * 有些播放器（酷我）的歌词响应不是走 `bytes()` / `string()` / okio 读取出口，而是自己拿
 * InputStream 读，所以只能在这里旁路：只把读到的前若干字节抄一份回看，绝不改变读取行为。
 * 一旦开头明显不是歌词/JSON/gzip（音频流、图片流），立刻停手，不浪费内存。
 */
internal class KuwoStreamTee(
    private val input: InputStream,
    private val onPayload: (ByteArray) -> Unit
) : InputStream() {

    private val sink = ByteArrayOutputStream(64 * 1024)
    private var stopped = false
    private var decided = false

    private fun feed(buffer: ByteArray, offset: Int, length: Int) {
        if (stopped || length <= 0) return
        if (sink.size() + length > MAX) {
            stop()
            return
        }
        sink.write(buffer, offset, length)
        if (!decided && sink.size() >= HEAD_SIZE) {
            decided = true
            if (!looksLikeLyricPayloadHead(sink.toByteArray())) stop()
        }
    }

    private fun stop() {
        stopped = true
        sink.reset()
    }

    private fun finish() {
        if (stopped) return
        stopped = true
        val bytes = sink.toByteArray()
        sink.reset()
        // 太短的流（几字节的错误页之类）直接放过，避免「酷我歌词解码失败（2 字节）」这种噪音
        if (bytes.size >= MIN) runCatching { onPayload(bytes) }
    }

    override fun read(): Int {
        val value = input.read()
        if (value < 0) {
            finish()
        } else {
            feed(byteArrayOf(value.toByte()), 0, 1)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = input.read(b, off, len)
        if (read < 0) finish() else feed(b, off, read)
        return read
    }

    override fun skip(n: Long): Long = input.skip(n)

    override fun available(): Int = input.available()

    override fun close() {
        try {
            input.close()
        } finally {
            finish()
        }
    }

    private companion object {
        const val MAX = 1_500_000
        const val MIN = 32
        const val HEAD_SIZE = 32
    }
}
