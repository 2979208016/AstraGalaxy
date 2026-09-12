package io.github.proify.lyricon.xinghe.xposed

import java.util.zip.InflaterInputStream

/**
 * QQ 音乐 QRC 歌词解密（3DES + zlib），从 LyricProvider 项目 qrckit 移植。
 *
 * 本地适配说明：QQ 音乐会把当前歌曲的歌词缓存到
 * `Android/data/com.tencent.qqmusic/files/qqmusic/qrc/<md5>.qrc`，
 * 文件正文即 16 进制密文，调用 [decrypt] 即可得到含 [ti:]/[ar:] 的 QRC 明文。
 */
/** QQ 歌词 QRC 解密（3DES + zlib），从 LyricProvider 项目 qrckit 移植 */
internal object QrcDecrypt {

    private val QQ_KEY = "!@#)(*\$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)

    private val decryptSchedule = Array(3) { Array(16) { ByteArray(6) } }.also {
        QqDESHelper.tripleDESKeySetup(QQ_KEY, it, QqDESHelper.DECRYPT)
    }

    fun decrypt(encrypted: String?): String? {
        if (encrypted.isNullOrBlank()) return null
        if (!isHexString(encrypted)) return encrypted
        return runCatching {
            val encryptedBytes = hexToBytes(encrypted)
            val decrypted = ByteArray(encryptedBytes.size)
            val temp = ByteArray(8)
            val block = ByteArray(8)
            for (i in encryptedBytes.indices step 8) {
                val size = if (i + 8 <= encryptedBytes.size) 8 else encryptedBytes.size - i
                System.arraycopy(encryptedBytes, i, block, 0, size)
                QqDESHelper.tripleDESCrypt(block, temp, decryptSchedule)
                System.arraycopy(temp, 0, decrypted, i, size)
            }
            InflaterInputStream(decrypted.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun isHexString(input: String): Boolean {
        if (input.isEmpty() || input.length % 2 != 0) return false
        for (c in input) {
            if (c !in '0'..'9' && c !in 'a'..'f' && c !in 'A'..'F') return false
        }
        return true
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2).apply {
            for (i in indices) this[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}

/** 3DES 实现（从 LyricProvider 项目 qrckit DESHelper 移植） */
internal object QqDESHelper {
    const val ENCRYPT = 1
    const val DECRYPT = 0

    private val box1 = intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8, 4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13)
    private val box2 = intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5, 0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9)
    private val box3 = intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1, 13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12)
    private val box4 = intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9, 10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14)
    private val box5 = intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6, 4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3)
    private val box6 = intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8, 9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13)
    private val box7 = intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6, 1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12)
    private val box8 = intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2, 7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11)

    private val keyRndShift = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val keyPermC = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35)
    private val keyPermD = intArrayOf(62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3)
    private val keyCompression = intArrayOf(13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31)

    private fun getBitFromByteArray(a: ByteArray, b: Int, c: Int): Int {
        val byteVal = a[(b / 32 * 4 + 3 - b % 32 / 8)].toInt() and 0xFF
        return (byteVal ushr (7 - (b % 8)) and 0x01) shl c
    }

    private fun getBitFromIntR(a: Int, b: Int, c: Int): Int = ((a ushr (31 - b)) and 0x01) shl c
    private fun getBitFromIntL(a: Int, b: Int, c: Int): Int = ((a shl b) and (-0x80000000)) ushr c
    private fun formatSBoxInput(a: Int): Int = (a and 0x20) or ((a and 0x1f) ushr 1) or ((a and 0x01) shl 4)

    fun tripleDESKeySetup(key: ByteArray, schedule: Array<Array<ByteArray>>, mode: Int) {
        if (mode == ENCRYPT) {
            keySchedule(key, 0, schedule[0], ENCRYPT)
            keySchedule(key, 8, schedule[1], DECRYPT)
            keySchedule(key, 16, schedule[2], ENCRYPT)
        } else {
            keySchedule(key, 0, schedule[2], DECRYPT)
            keySchedule(key, 8, schedule[1], ENCRYPT)
            keySchedule(key, 16, schedule[0], DECRYPT)
        }
    }

    fun tripleDESCrypt(input: ByteArray, output: ByteArray, key: Array<Array<ByteArray>>) {
        crypt(input, output, key[0])
        crypt(output, output, key[1])
        crypt(output, output, key[2])
    }

    private fun keySchedule(key: ByteArray, offset: Int, schedule: Array<ByteArray>, mode: Int) {
        var c = 0
        var d = 0
        for (i in 0 until 28) {
            c = c or getBitFromByteArray(key, keyPermC[i] + offset * 8, 31 - i)
            d = d or getBitFromByteArray(key, keyPermD[i] + offset * 8, 31 - i)
        }
        for (i in 0 until 16) {
            c = ((c shl keyRndShift[i]) or (c ushr (28 - keyRndShift[i]))) and -0x10
            d = ((d shl keyRndShift[i]) or (d ushr (28 - keyRndShift[i]))) and -0x10
            val toGen = if (mode == DECRYPT) 15 - i else i
            schedule[toGen].fill(0)
            for (k in 0 until 24) {
                schedule[toGen][k / 8] = (schedule[toGen][k / 8].toInt() or getBitFromIntR(c, keyCompression[k], 7 - (k % 8))).toByte()
            }
            for (k in 24 until 48) {
                schedule[toGen][k / 8] = (schedule[toGen][k / 8].toInt() or getBitFromIntR(d, keyCompression[k] - 27, 7 - (k % 8))).toByte()
            }
        }
    }

    private fun initialPermutation(state: IntArray, input: ByteArray) {
        val perm = intArrayOf(57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3, 61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7)
        var v0 = 0
        var v1 = 0
        for (i in 0 until 32) v0 = v0 or getBitFromByteArray(input, perm[i], 31 - i)
        val perm2 = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6)
        for (i in 0 until 32) v1 = v1 or getBitFromByteArray(input, perm2[i], 31 - i)
        state[0] = v0
        state[1] = v1
    }

    private fun inverseInitialPermutation(state: IntArray, output: ByteArray) {
        val outIndices = intArrayOf(3, 2, 1, 0, 7, 6, 5, 4)
        val bitOffsets = intArrayOf(7, 6, 5, 4, 3, 2, 1, 0)
        for (i in 0 until 8) {
            output[outIndices[i]] = (
                getBitFromIntR(state[1], bitOffsets[i], 7) or getBitFromIntR(state[0], bitOffsets[i], 6) or
                    getBitFromIntR(state[1], bitOffsets[i] + 8, 5) or getBitFromIntR(state[0], bitOffsets[i] + 8, 4) or
                    getBitFromIntR(state[1], bitOffsets[i] + 16, 3) or getBitFromIntR(state[0], bitOffsets[i] + 16, 2) or
                    getBitFromIntR(state[1], bitOffsets[i] + 24, 1) or getBitFromIntR(state[0], bitOffsets[i] + 24, 0)
                ).toByte()
        }
    }

    private fun f(stateIn: Int, key: ByteArray): Int {
        val t1 = getBitFromIntL(stateIn, 31, 0) or ((stateIn and -0x10000000) ushr 1) or getBitFromIntL(stateIn, 4, 5) or getBitFromIntL(stateIn, 3, 6) or
            ((stateIn and 0x0f000000) ushr 3) or getBitFromIntL(stateIn, 8, 11) or getBitFromIntL(stateIn, 7, 12) or ((stateIn and 0x00f00000) ushr 5) or
            getBitFromIntL(stateIn, 12, 17) or getBitFromIntL(stateIn, 11, 18) or ((stateIn and 0x000f0000) ushr 7) or getBitFromIntL(stateIn, 16, 23)
        val t2 = getBitFromIntL(stateIn, 15, 0) or ((stateIn and 0x0000f000) shl 15) or getBitFromIntL(stateIn, 20, 5) or getBitFromIntL(stateIn, 19, 6) or
            ((stateIn and 0x00000f00) shl 13) or getBitFromIntL(stateIn, 24, 11) or getBitFromIntL(stateIn, 23, 12) or ((stateIn and 0x000000f0) shl 11) or
            getBitFromIntL(stateIn, 28, 17) or getBitFromIntL(stateIn, 27, 18) or ((stateIn and 0x0000000f) shl 9) or getBitFromIntL(stateIn, 0, 23)

        val x0 = ((t1 ushr 24) and 0xFF) xor (key[0].toInt() and 0xFF)
        val x1 = ((t1 ushr 16) and 0xFF) xor (key[1].toInt() and 0xFF)
        val x2 = ((t1 ushr 8) and 0xFF) xor (key[2].toInt() and 0xFF)
        val x3 = ((t2 ushr 24) and 0xFF) xor (key[3].toInt() and 0xFF)
        val x4 = ((t2 ushr 16) and 0xFF) xor (key[4].toInt() and 0xFF)
        val x5 = ((t2 ushr 8) and 0xFF) xor (key[5].toInt() and 0xFF)

        val st = (box1[formatSBoxInput(x0 ushr 2)] shl 28) or (box2[formatSBoxInput(((x0 and 0x03) shl 4) or (x1 ushr 4))] shl 24) or
            (box3[formatSBoxInput(((x1 and 0x0f) shl 2) or (x2 ushr 6))] shl 20) or (box4[formatSBoxInput(x2 and 0x3f)] shl 16) or
            (box5[formatSBoxInput(x3 ushr 2)] shl 12) or (box6[formatSBoxInput(((x3 and 0x03) shl 4) or (x4 ushr 4))] shl 8) or
            (box7[formatSBoxInput(((x4 and 0x0f) shl 2) or (x5 ushr 6))] shl 4) or box8[formatSBoxInput(x5 and 0x3f)]

        return getBitFromIntL(st, 15, 0) or getBitFromIntL(st, 6, 1) or getBitFromIntL(st, 19, 2) or getBitFromIntL(st, 20, 3) or
            getBitFromIntL(st, 28, 4) or getBitFromIntL(st, 11, 5) or getBitFromIntL(st, 27, 6) or getBitFromIntL(st, 16, 7) or
            getBitFromIntL(st, 0, 8) or getBitFromIntL(st, 14, 9) or getBitFromIntL(st, 22, 10) or getBitFromIntL(st, 25, 11) or
            getBitFromIntL(st, 4, 12) or getBitFromIntL(st, 17, 13) or getBitFromIntL(st, 30, 14) or getBitFromIntL(st, 9, 15) or
            getBitFromIntL(st, 1, 16) or getBitFromIntL(st, 7, 17) or getBitFromIntL(st, 23, 18) or getBitFromIntL(st, 13, 19) or
            getBitFromIntL(st, 31, 20) or getBitFromIntL(st, 26, 21) or getBitFromIntL(st, 2, 22) or getBitFromIntL(st, 8, 23) or
            getBitFromIntL(st, 18, 24) or getBitFromIntL(st, 12, 25) or getBitFromIntL(st, 29, 26) or getBitFromIntL(st, 5, 27) or
            getBitFromIntL(st, 21, 28) or getBitFromIntL(st, 10, 29) or getBitFromIntL(st, 3, 30) or getBitFromIntL(st, 24, 31)
    }

    private fun crypt(input: ByteArray, output: ByteArray, key: Array<ByteArray>) {
        val state = IntArray(2)
        initialPermutation(state, input)
        for (idx in 0 until 15) {
            val t = state[1]
            state[1] = f(state[1], key[idx]) xor state[0]
            state[0] = t
        }
        state[0] = f(state[1], key[15]) xor state[0]
        inverseInitialPermutation(state, output)
    }
}
