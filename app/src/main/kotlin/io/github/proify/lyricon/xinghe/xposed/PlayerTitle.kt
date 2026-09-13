package io.github.proify.lyricon.xinghe.xposed

/**
 * 播放器 MediaMetadata 的「歌名 / 歌手」清洗。
 *
 * 酷狗音乐（.support 进程）与 QQ 音乐（QQPlayerService）会把「当前歌词行」当成
 * 媒体标题发出来（锁屏歌词），真正的歌曲信息藏在歌手字段里：
 *   酷狗：TITLE = 歌词行 / 「作词：xxx」这类制作信息，ARTIST = 「歌手-歌名」
 *   QQ  ：TITLE = 歌词行，ARTIST = 「歌名-歌手」
 * 若原样当成切歌信号，就会出现「歌名变成歌词」「歌词提供反复失效」「词不对歌」。
 *
 * 处理策略（从强到弱）：
 *  1. 标题命中当前歌词行 / 制作信息行 → 一定不是歌名；
 *  2. 歌手字段是「A-B」结构，且其中一段就是当前歌曲 → 标题是歌词行，用歌手字段还原；
 *  3. 歌手字段是「A-B」结构，且宿主平台确实这么干（酷狗系 / QQ 音乐）→ 用歌手字段还原；
 *  4. 其余情况原样使用标题。
 */
internal object PlayerTitle {

    /** 制作信息行前缀：酷狗 / QQ 会把它们当作标题发出来 */
    private val CREDIT_PREFIX = listOf(
        "作词", "作曲", "编曲", "制作", "混音", "母带", "录音", "和声", "监制", "统筹",
        "吉他", "贝斯", "键盘", "弦乐", "出品", "发行", "策划", "鸣谢", "词曲",
        "词：", "曲：", "词:", "曲:", "OP", "SP", "本作品", "未经许可", "未经授权",
        "版权", "原唱", "翻唱", "后期", "美术", "设计", "配唱", "人声", "企划"
    )

    private val SEPARATORS = listOf(" - ", "–", "—", "-", "－", "_")

    /** 清洗结果：歌名 / 歌手 + 是否仍属于当前歌曲 */
    internal class Resolved(
        val name: String?,
        val artist: String?,
        val sameSong: Boolean,
        val suspectTitle: Boolean
    )

    /**
     * @param currentName 当前歌曲名（上一轮清洗结果）
     * @param currentArtist 当前歌手
     * @param lyricLines 当前已发布歌词的归一化行集合：标题命中它 → 一定是歌词行
     * @param songFirstArtistLast ARTIST 是「歌名-歌手」（QQ 音乐）还是「歌手-歌名」（酷狗系）
     * @param trustArtistStructure 宿主平台确实会把「歌名-歌手」写进 ARTIST 字段
     */
    fun resolve(
        title: String?,
        artist: String?,
        currentName: String?,
        currentArtist: String?,
        lyricLines: Collection<String>,
        songFirstArtistLast: Boolean,
        trustArtistStructure: Boolean = false
    ): Resolved {
        val rawTitle = title?.trim().orEmpty()
        val rawArtist = artist?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val suspect = isSuspectTitle(rawTitle, lyricLines)

        val curName = normalize(currentName)
        val curArtist = normalize(currentArtist)
        val parts = splitArtist(rawArtist)
        val structureHoldsCurrentSong = parts != null &&
            (matches(curName, parts.first) || matches(curName, parts.second) ||
                matches(curArtist, parts.first) || matches(curArtist, parts.second))

        // 歌手字段里带着「歌名-歌手」时，它比标题更可信
        if (parts != null && (suspect || structureHoldsCurrentSong || trustArtistStructure)) {
            val (a, b) = parts
            val ordered = when {
                matches(curName, a) -> a to b
                matches(curName, b) -> b to a
                matches(curArtist, a) -> b to a
                matches(curArtist, b) -> a to b
                songFirstArtistLast -> a to b
                else -> b to a
            }
            val name = ordered.first.trim().ifEmpty { null } ?: currentName
            val singer = ordered.second.trim().ifEmpty { null } ?: currentArtist
            return Resolved(name, singer, sameAs(currentName, name), suspectTitle = suspect)
        }

        if (rawTitle.isNotEmpty() && !suspect) {
            return Resolved(rawTitle, rawArtist, sameAs(currentName, rawTitle), suspectTitle = false)
        }

        // 还原不了：绝不能把歌词行当歌名显示；但如果标题已经变了，
        // 说明多半真的切歌了 —— 照样触发歌词重查，只是继续沿用上一首歌名，
        // 避免出现「整条元数据被忽略 → 歌词再也不刷新」的老问题。
        if (currentName == null) {
            // 第一首歌：没有旧歌名可用，只能用原始标题
            return Resolved(
                rawTitle.ifEmpty { null },
                rawArtist,
                sameSong = false,
                suspectTitle = suspect
            )
        }
        return Resolved(
            currentName,
            currentArtist ?: rawArtist,
            sameSong = sameAs(currentName, rawTitle),
            suspectTitle = true
        )
    }

    /** 标题是不是「歌词行 / 制作信息行」 */
    fun isSuspectTitle(title: String?, lyricLines: Collection<String> = emptyList()): Boolean {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return true
        val key = normalize(t)
        if (key.isEmpty()) return true
        // 注意：不能拿「标题命中当前歌词行」当判据。副歌那句往往就是歌名本身
        //（例：孙燕姿《我不难过》，歌词里就有「我不难过」），一旦命中就把真实切歌
        //  当成「歌词行伪装的元数据」忽略掉，表现是歌名/歌词再也不刷新。
        //  酷狗系 / QQ 的歌词行伪装由 ARTIST 的「歌名-歌手」结构识别，不依赖这条。
        if (CREDIT_PREFIX.any { t.startsWith(it) }) return true
        if (t.startsWith("【") || t.startsWith("［") || t.startsWith("[")) return true
        if (t.startsWith("（") && t.endsWith("）")) return true
        return false
    }

    /** 归一化：只保留字母 / 数字 / 中日韩文字 */
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) if (c.isLetterOrDigit()) sb.append(c)
        return sb.toString()
    }

    private fun matches(current: String, candidate: String?): Boolean {
        val c = normalize(candidate)
        return current.isNotEmpty() && c.isNotEmpty() && (c == current || c.contains(current) || current.contains(c))
    }

    private fun sameAs(current: String?, candidate: String?): Boolean {
        val a = normalize(current)
        val b = normalize(candidate)
        return a.isNotEmpty() && a == b
    }

    /**
     * 「A-B」结构：拆成左右两段。
     * 两段都必须至少 2 个有效字符，避免把「A-Lin」这种歌手名拆成歌名/歌手。
     */
    private fun splitArtist(artist: String?): Pair<String, String>? {
        if (artist.isNullOrBlank()) return null
        for (sep in SEPARATORS) {
            val idx = artist.indexOf(sep)
            if (idx <= 0) continue
            val left = artist.substring(0, idx).trim()
            val right = artist.substring(idx + sep.length).trim()
            if (left.length < 2 || right.length < 2) continue
            if (left.length > 60 || right.length > 60) continue
            if (left.none { it.isLetterOrDigit() } || right.none { it.isLetterOrDigit() }) continue
            return left to right
        }
        return null
    }
}
