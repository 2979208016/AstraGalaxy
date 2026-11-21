package io.github.proify.astrafirmament.lyric

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 本地歌词文件定位器（从 LyricLocal.kt 拆出——Kotlin object 成员须同文件，
 * LocalLyricFinder 本就是独立 object，按规范一主要类一文件拆分）。
 *
 * 扫描各音乐 App 的歌词缓存目录，按文件名/歌手/时长证据匹配当前歌曲。
 */
/**
 * 本地歌词扫描与匹配（完全离线）。
 *
 * 匹配按「证据强度」打分，而不是碰运气：
 *   精确歌名(正文 [ti:])       +120
 *   文件名/首行含歌名          +60 / +40
 *   文件名含歌手                +20
 *   mediaId / 文件名 == 歌曲 id +150
 *   最近写入(≤2min / ≤15min)   +25 / +8
 *   总时长吻合(≤5s / ≤15s)     +25 / +12
 * 分数达到 [ACCEPT_SCORE] 才认；都匹配不上时走「时长 + 写入窗口 + 身份」兜底，
 * 缺任一条证据就不认领——宁可不出词，也不能把上一首的歌词推给这一首。
 */
internal object LocalLyricFinder {

    /** 单次匹配最多解析的候选文件数（按修改时间倒序） */
    private const val MAX_CANDIDATES = 120

    /** 认定为「匹配上」的最低分 */
    private const val ACCEPT_SCORE = 60

    /** 兜底：刚写入的缓存认领时间窗 */
    private const val RECENT_WINDOW_MS = 120_000L

    /** 兜底：按总时长匹配的容差 */
    private const val DURATION_TOLERANCE_MS = 8_000L

    /** 兜底：缓存「刚写入」窗口（文件名不含歌名/id 的平台，如 QQ 音乐） */
    private const val FRESH_WRITE_MS = 15_000L

    /** 兜底：允许缓存比本首歌开始时间早多久（写入耗时） */
    private const val WRITE_GRACE_MS = 2_000L

    /**
     * 网易云专属：时长容差收紧到 3s。
     * 它的缓存正文没有歌名、只有文件名（=歌曲 id）算身份，容差宽了就会把
     * 隔壁歌的缓存按「时长差不多」认进来。
     */
    private const val NETEASE_DURATION_TOLERANCE_MS = 3_000L

    // 酷我专属：时长容差收紧 —— hash 文件名 + [ti:] 经常为空，
    // 唯一硬证据就是时长；8s 对流行歌太宽，会把别的歌认进来
    private const val KUWO_DURATION_TOLERANCE_MS = 3_000L

    // 酷我专属：缓存写入距切歌点的窗口（酷我会提前下载下一首，
    // mtime 可比切歌早 30s；过期的老缓存不算）
    private const val KUWO_WRITE_BEFORE_MS = 30_000L
    private const val KUWO_WRITE_AFTER_MS = 120_000L

    private val HASH_SUFFIX = Regex("""-[0-9a-fA-F]{16,}$|-(\d{5,})$""")

    /** 私有目录扫描的深度与规模上限 */
    private const val MAX_SWEEP_DEPTH = 4
    private const val MAX_SWEEP_FILES = 400
    private const val MAX_SWEEP_FILE_BYTES = 2L * 1024 * 1024

    private val NAME_IN_JSON = Regex(""""Name"\s*:\s*"([^"]+)"""")

    /** path -> (mtime, 解析结果)，避免重复解析 */
    private val cache = ConcurrentHashMap<String, Entry>()

    private class Entry(val mtime: Long, val lyric: LocalLyric?)

    /** 一次目录扫描的结果：全部候选 + 最高分 */
    private class ScanResult(
        val parsed: List<Pair<File, LocalLyric>>,
        val bestScore: Int,
        val bestLyric: LocalLyric?,
        val bestFile: File?
    )

    /** 兜底候选：strong 有身份证据，weak 只靠时长 + 写入窗口 */
    private class Evidence(
        val strong: Pair<File, LocalLyric>?,
        val weak: List<Pair<File, LocalLyric>>
    )

    fun find(
        context: Context,
        recipe: LocalRecipe,
        title: String,
        artist: String?,
        durationMs: Long,
        mediaId: String?,
        songStartedAtMs: Long = 0L,
        log: (String) -> Unit
    ): LocalLyric? {
        val wantTitle = normalize(title)
        val wantCore = titleCore(title)
        val wantArtist = normalize(artist)
        val now = System.currentTimeMillis()

        // 索引快速通道：之前扫过并记下的「歌曲 id / 歌名 / 歌手 → 文件」直接命中
        indexLookup(context, recipe, mediaId, durationMs, wantTitle, wantCore, wantArtist, now, log)
            ?.let { return it }

        val scan = scanSources(context, recipe, wantTitle, wantCore, wantArtist, mediaId, durationMs, now, log)
        if (scan.bestLyric != null && scan.bestScore >= ACCEPT_SCORE) {
            log("匹配成功 score=" + scan.bestScore + ": " + (scan.bestFile?.name ?: ""))
            return scan.bestLyric
        }
        claimByEvidence(recipe, scan.parsed, wantCore, wantArtist, durationMs, mediaId, songStartedAtMs, now, log)
            ?.let { return it }

        log("未匹配 (最佳分=" + scan.bestScore + ")")
        logTopCandidates(scan.parsed, wantTitle, wantCore, wantArtist, mediaId, durationMs, now, log)
        return null
    }

    /** 扫一遍配方目录，按证据强度打分取最高分 */
    private fun scanSources(
        context: Context,
        recipe: LocalRecipe,
        wantTitle: String,
        wantCore: String,
        wantArtist: String,
        mediaId: String?,
        durationMs: Long,
        now: Long,
        log: (String) -> Unit
    ): ScanResult {
        var bestScore = Int.MIN_VALUE
        var bestLyric: LocalLyric? = null
        var bestFile: File? = null
        val parsed = ArrayList<Pair<File, LocalLyric>>()

        for (src in recipe.sources) {
            val dir = resolveDir(context, src) ?: continue
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            val candidates = files
                .filter { it.isFile && matchesExt(it, src.ext) }
                .sortedByDescending { it.lastModified() }
                .take(MAX_CANDIDATES)

            for (file in candidates) {
                val lyric = parse(context, file, src.format) ?: continue
                if (lyric.lines.isEmpty()) continue
                parsed += file to lyric
                var score = scoreOf(file, lyric, wantTitle, wantCore, wantArtist, mediaId, durationMs, now)
                if (src.format == LyricFormat.QRC) {
                    // qrc 文件名是 md5、正文加密，唯一的身份证据是旁边的 <同名>.producer
                    when (producerEvidence(file, wantArtist)) {
                        true -> {
                            score += 150
                            log(file.name + " 演唱名单吻合")
                        }
                        false -> score -= 500
                        null -> Unit
                    }
                }
                if (score > bestScore) {
                    bestScore = score
                    bestLyric = lyric
                    bestFile = file
                }
            }
            log("扫描 " + dir.absolutePath + ": 共 " + files.size + " 个文件, 解析 " + candidates.size + " 个")
        }
        return ScanResult(parsed, bestScore, bestLyric, bestFile)
    }

    /**
     * 兜底认领：证据不足就不认领——宁可不出词，也不能把上一首的歌词推给这一首。
     *
     * 必须同时满足：
     *  1. 总时长吻合（网易云/酷我 3s，其余 8s）；
     *  2. 缓存写在本首歌的播放窗口内（预下载允许提前，见 WRITE_GRACE_MS）；
     *  3. 身份证据：文件名或正文里的歌曲 id、正文 [ti:]/[ar:]，或 QQ 的 .producer 名单。
     *
     * 网易云的缓存正文没有歌名，唯一身份就是文件名（=歌曲 id），所以「刚写入」
     * 本身不算证据——这正是《一张相片》被推成《心墙》的原因。
     */
    private fun claimByEvidence(
        recipe: LocalRecipe,
        parsed: List<Pair<File, LocalLyric>>,
        wantCore: String,
        wantArtist: String,
        durationMs: Long,
        mediaId: String?,
        startedAt: Long,
        now: Long,
        log: (String) -> Unit
    ): LocalLyric? {
        if (durationMs <= 0L) return null
        val found = evidenceOf(recipe, parsed, wantCore, wantArtist, durationMs, mediaId, startedAt, now, log)
        found.strong?.let {
            log("兜底命中(时长+身份): " + it.first.name)
            return it.second
        }
        val weak = found.weak
        if (weak.size == 1) {
            log("兜底命中(时长+窗口·唯一候选): " + weak[0].first.name)
            return weak[0].second
        }
        if (weak.size > 1) log("放弃兜底：" + weak.size + " 个候选都缺身份证据")
        return null
    }

    /** 把候选分成「有身份证据」和「只有时长+写入窗口」两档 */
    private fun evidenceOf(
        recipe: LocalRecipe,
        parsed: List<Pair<File, LocalLyric>>,
        wantCore: String,
        wantArtist: String,
        durationMs: Long,
        mediaId: String?,
        startedAt: Long,
        now: Long,
        log: (String) -> Unit
    ): Evidence {
        val mid = normalize(mediaId)
        // 网易云：文件名就是歌曲 id，没有 id 证据就绝不认（mid 缺失时才允许唯一候选）
        val idNamed = recipe.sources.any { it.format == LyricFormat.NETEASE }
        val opaqueName = recipe.sources.any { it.format == LyricFormat.QRC }
        val kuwoMode = recipe.sources.any { it.format == LyricFormat.KUWO_DAT }
        val tolerance = when {
            kuwoMode -> KUWO_DURATION_TOLERANCE_MS
            idNamed -> NETEASE_DURATION_TOLERANCE_MS
            else -> DURATION_TOLERANCE_MS
        }
        var strong: Pair<File, LocalLyric>? = null
        var strongDiff = Long.MAX_VALUE
        val weak = ArrayList<Pair<File, LocalLyric>>()
        for ((file, lyric) in parsed) {
            val last = lyric.lines.lastOrNull()?.begin ?: continue
            val diff = abs(last - durationMs)
            if (diff > tolerance) continue
            if (identityConflicts(lyric, wantCore, wantArtist, mid)) {
                log("排除 " + file.name + "：歌词自带身份与当前歌曲不符")
                continue
            }
            if (!inPlayWindow(file, startedAt, kuwoMode, now)) {
                log("排除 " + file.name + "：缓存不在本次播放窗口内")
                continue
            }
            val evidence = if (opaqueName) producerEvidence(file, wantArtist) else null
            if (evidence == false) {
                log("排除 " + file.name + "：演唱名单与当前歌手不符")
                continue
            }
            if (evidence == true || hasIdentity(file, lyric, wantCore, wantArtist, mid)) {
                if (diff < strongDiff) {
                    strongDiff = diff
                    strong = file to lyric
                }
            } else if (!idNamed || mid.isEmpty()) {
                weak += file to lyric
            }
        }
        return Evidence(strong, weak)
    }

    /** 缓存是否写在本首歌的播放窗口内；宿主没报起点时退回「刚写入」 */
    private fun inPlayWindow(file: File, startedAt: Long, kuwoMode: Boolean, now: Long): Boolean {
        val writtenAt = file.lastModified()
        if (startedAt > 0L) {
            if (kuwoMode) {
                return writtenAt >= startedAt - KUWO_WRITE_BEFORE_MS &&
                    writtenAt <= startedAt + KUWO_WRITE_AFTER_MS
            }
            return writtenAt >= startedAt - WRITE_GRACE_MS
        }
        return now - writtenAt in 0..FRESH_WRITE_MS
    }

    /** 文件名/正文里的歌曲 id、[ti:] 歌名、[ar:] 歌手——任一对得上才算身份证据 */
    private fun hasIdentity(
        file: File,
        lyric: LocalLyric,
        wantCore: String,
        wantArtist: String,
        mid: String
    ): Boolean {
        if (mid.isNotEmpty()) {
            if (normalize(file.name.substringBeforeLast('.')) == mid) return true
            if (normalize(lyric.id) == mid) return true
        }
        val embeddedCore = titleCore(lyric.title)
        if (embeddedCore.isNotEmpty() && wantCore.isNotEmpty() &&
            (embeddedCore == wantCore || embeddedCore.contains(wantCore) || wantCore.contains(embeddedCore))
        ) return true
        val embeddedArtist = normalize(lyric.artist)
        if (embeddedArtist.isNotEmpty() && wantArtist.isNotEmpty() &&
            (embeddedArtist.contains(wantArtist) || wantArtist.contains(embeddedArtist))
        ) return true
        return false
    }

    /** 失败时把前几名候选打出来，方便定位「为什么没认出来」 */
    private fun logTopCandidates(
        parsed: List<Pair<File, LocalLyric>>,
        wantTitle: String,
        wantCore: String,
        wantArtist: String,
        mediaId: String?,
        durationMs: Long,
        now: Long,
        log: (String) -> Unit
    ) {
        runCatching {
            parsed.map { (file, lyric) ->
                scoreOf(file, lyric, wantTitle, wantCore, wantArtist, mediaId, durationMs, now) to file.name
            }.sortedByDescending { it.first }
                .take(3)
                .joinToString(" | ") { it.second + "=" + it.first }
        }.getOrNull()?.let { log("候选: $it") }
    }

    /**
     * 兜底通道：直接扫宿主自己的私有目录（files / cache 及其外部对应目录，深度受限），
     * 找长得像歌词的文件。
     *
     * 有些播放器（酷我）的歌词缓存既不在外部存储的固定位置、请求也不走 okhttp，
     * 之前只推得出歌曲名和歌手。这里用同一套打分规则从它自己的目录里把歌词找出来。
     */
    fun findInOwnStorage(
        context: Context,
        title: String,
        artist: String?,
        durationMs: Long,
        mediaId: String?,
        songStartedAtMs: Long,
        log: (String) -> Unit
    ): LocalLyric? {
        val wantTitle = normalize(title)
        if (wantTitle.isEmpty()) return null
        val wantArtist = normalize(artist)
        val wantCore = titleCore(title)
        val roots = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            runCatching { context.getExternalFilesDir(null) }.getOrNull(),
            runCatching { context.getExternalCacheDir() }.getOrNull()
        )
        if (roots.isEmpty()) return null
        val now = System.currentTimeMillis()
        var bestScore = Int.MIN_VALUE
        var best: LocalLyric? = null
        var scanned = 0
        // 酷我的歌词固定落在 KuwoMusic/data/LYRICS_CACHE，其它目录
        //（.fresco 图片缓存、广告 dat 等）动辄几千个文件，先把 400 个名额
        // 吃光就走不到真正含歌词的目录。这里按「名字像歌词缓存的目录」排前，
        // 保证 LYRICS_CACHE / lyric / lrc 目录最先被扫。
        val ordered = roots.sortedByDescending { root ->
            var hit = 0
            runCatching {
                root.listFiles()?.forEach { child ->
                    val n = child.name.lowercase()
                    if (n.contains("lyric") || n.contains("lrc")) hit++
                }
            }
            hit
        }
        for (root in ordered) {
            walkLyricFiles(root, 0) { file ->
                if (scanned >= MAX_SWEEP_FILES) return@walkLyricFiles
                val format = sweepFormatOf(file) ?: return@walkLyricFiles
                if (file.length() > MAX_SWEEP_FILE_BYTES) return@walkLyricFiles
                scanned++
                val lyric = parse(context, file, format) ?: return@walkLyricFiles
                if (lyric.lines.size < 3) return@walkLyricFiles
                var score = scoreOf(file, lyric, wantTitle, wantCore, wantArtist, mediaId, durationMs, now)
                if (format == LyricFormat.QRC) {
                    when (producerEvidence(file, wantArtist)) {
                        true -> score += 150
                        false -> score -= 500
                        null -> Unit
                    }
                }
                if (score > bestScore) {
                    bestScore = score
                    best = lyric
                }
            }
        }
        log("私有目录扫描：" + scanned + " 个歌词文件，最佳分=" + bestScore)
        return if (best != null && bestScore >= ACCEPT_SCORE) best else null
    }

    private fun walkLyricFiles(dir: File, depth: Int, visit: (File) -> Unit) {
        if (depth > MAX_SWEEP_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        // 「名字像歌词」的目录先递归（酷我 KuwoMusic/data/LYRICS_CACHE 在第 3 层，
        // 不排前会被 .fresco 图片缓存先吃光文件名额）
        val sorted = children.sortedBy { child ->
            val n = child.name.lowercase()
            if (child.isDirectory && (n.contains("lyric") || n.contains("lrc"))) 0 else 1
        }
        for (child in sorted) {
            if (child.isDirectory) walkLyricFiles(child, depth + 1, visit)
            else if (child.isFile) visit(child)
        }
    }

    private fun sweepFormatOf(file: File): LyricFormat? {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".krc") -> LyricFormat.KRC
            name.endsWith(".qrc") -> LyricFormat.QRC
            name.endsWith(".lrc") -> LyricFormat.LRC
            name.endsWith(".lrcx") -> LyricFormat.LRCX
            name.endsWith(".alm3ll") -> LyricFormat.LRC
            // 酷我 LYRICS_CACHE 的 *.dat 与 MOBILEAD/QUKU 等其它 dat 同后缀，
            // 靠 KUWO_DAT 解析器的「解出来必须像 LRCX」兜底，认不出的自然丢弃
            name.endsWith(".dat") -> LyricFormat.KUWO_DAT
            else -> null
        }
    }

    /**
     * QQ 音乐的歌词目录里，每首歌词都配了一份 <同名>.producer（JSON，含演唱/作词等名字）。
     * qrc 本身加密、文件名是 md5，正文里读不到歌名，只能靠它验明正身：
     * 名单里没有当前歌手就排除，避免推出别的歌的歌词。
     */
    private fun producerEvidence(file: File, wantArtist: String): Boolean? {
        val names = producerNames(file) ?: return null
        if (wantArtist.isEmpty()) return null
        for (name in names) {
            val n = normalize(name)
            if (n.isEmpty()) continue
            if (n == wantArtist || n.contains(wantArtist) || wantArtist.contains(n)) return true
        }
        return false
    }

    private fun producerNames(file: File): List<String>? {
        val parent = file.parentFile ?: return null
        val base = file.name.substringBeforeLast('.', file.name)
        val producer = File(parent, base + ".producer")
        if (!producer.isFile || producer.length() <= 0L || producer.length() > 512 * 1024) return null
        val text = runCatching { producer.readText() }.getOrNull() ?: return null
        return NAME_IN_JSON.findAll(text).map { it.groupValues[1] }.toList()
    }

    private fun scoreOf(
        file: File,
        lyric: LocalLyric,
        wantTitle: String,
        wantCore: String,
        wantArtist: String,
        mediaId: String?,
        durationMs: Long,
        now: Long
    ): Int {
        if (wantTitle.isEmpty()) return 0
        var score = 0

        val fileStem = normalize(file.name.substringBeforeLast('.'))
        val fileBase = normalize(stripHash(file.name.substringBeforeLast('.')))
        val embeddedTitle = normalize(lyric.title)
        val embeddedArtist = normalize(lyric.artist)
        val firstLine = normalize(lyric.firstLine)
        val embeddedId = normalize(lyric.id)

        val embeddedCore = titleCore(lyric.title)
        if (embeddedCore.isNotEmpty() && wantCore.isNotEmpty()) {
            if (embeddedCore == wantCore) score += 120
            else if (embeddedCore.contains(wantCore) || wantCore.contains(embeddedCore)) score += 70
        } else if (embeddedTitle.isNotEmpty()) {
            if (embeddedTitle == wantTitle) score += 120
            else if (embeddedTitle.contains(wantTitle) || wantTitle.contains(embeddedTitle)) score += 70
        }
        if (wantCore.isNotEmpty()) {
            if (fileBase.contains(wantCore)) score += 60 else if (fileStem.contains(wantCore)) score += 30
            if (firstLine.contains(wantCore)) score += 40
        }

        if (wantArtist.length > 1) {
            if (embeddedArtist == wantArtist) score += 30
            else if (embeddedArtist.isNotEmpty() &&
                (embeddedArtist.contains(wantArtist) || wantArtist.contains(embeddedArtist))
            ) score += 15
            if (fileBase.contains(wantArtist)) score += 20
            if (firstLine.contains(wantArtist)) score += 15
        }

        if (!mediaId.isNullOrBlank()) {
            val mid = normalize(mediaId)
            if (mid.isNotEmpty() &&
                (embeddedId == mid || fileStem == mid || fileStem.endsWith(mid))
            ) score += 150
        }

        val age = now - file.lastModified()
        if (age <= RECENT_WINDOW_MS) score += 25 else if (age <= 900_000L) score += 8
        // 酷我 dat：文件名是 hash、[ti:] 常为空，「最近写入 + 时长吻合」是唯一身份证明
        if (file.name.endsWith(".dat", true)) {
            if (age <= RECENT_WINDOW_MS) score += 40
            if (durationMs > 0) {
                val lastBegin = lyric.lines.lastOrNull()?.begin ?: 0L
                if (lastBegin > 0 && abs(lastBegin - durationMs) <= KUWO_DURATION_TOLERANCE_MS) {
                    score += 80
                }
            }
        }

        if (durationMs > 0) {
            val diff = abs(lyric.lines.last().begin - durationMs)
            if (diff <= 5_000L) score += 25 else if (diff <= 15_000L) score += 12
        }
        return score
    }

    private fun parse(context: Context, file: File, format: LyricFormat): LocalLyric? {
        val path = file.absolutePath
        val mtime = file.lastModified()
        cache[path]?.let { if (it.mtime == mtime) return it.lyric }
        val lyric = runCatching {
            when (format) {
                LyricFormat.KRC -> LyricParsers.parseKrc(file.readBytes())
                LyricFormat.QRC -> LyricParsers.parseQrcFile(file.readBytes())
                LyricFormat.LRC -> LyricParsers.parseLrcText(file.readText())
                LyricFormat.NETEASE -> LyricParsers.parseNetease(file.readText())
                LyricFormat.LRCX -> {
                    val text = file.readText()
                    LyricParsers.parseKuwoLrcx(text) ?: LyricParsers.parseLrcText(text)
                }
                LyricFormat.KUWO_DAT -> LyricParsers.parseKuwoDat(file.readBytes())
            }
        }.getOrNull()
        if (cache.size > 600) cache.clear()
        cache[path] = Entry(mtime, lyric)
        if (lyric != null && lyric.lines.isNotEmpty()) {
            runCatching { LyricIndex.remember(context, file, lyric) }
        }
        return lyric
    }

    /** 索引命中：直接用之前记录过的文件，跳过整目录扫描 */
    private fun indexLookup(
        context: Context,
        recipe: LocalRecipe,
        mediaId: String?,
        durationMs: Long,
        wantTitle: String,
        wantCore: String,
        wantArtist: String,
        now: Long,
        log: (String) -> Unit
    ): LocalLyric? {
        val files = LyricIndex.lookup(context, wantTitle, wantArtist, mediaId)
        if (files.isEmpty()) return null
        for (file in files) {
            val format = formatOf(recipe, file) ?: continue
            val lyric = parse(context, file, format) ?: continue
            if (lyric.lines.isEmpty()) continue
            val score = scoreOf(file, lyric, wantTitle, wantCore, wantArtist, mediaId, durationMs, now)
            if (score >= ACCEPT_SCORE) {
                log("索引命中 score=$score: ${file.name}")
                return lyric
            }
        }
        return null
    }

    /** 索引里的文件不带来源信息，按后缀回溯格式，兜底用配方的第一项 */
    private fun formatOf(recipe: LocalRecipe, file: File): LyricFormat? {
        val name = file.name.lowercase()
        for (src in recipe.sources) {
            if (src.ext.isNotEmpty() && src.ext.any { name.endsWith(".$it") }) return src.format
        }
        return when {
            name.endsWith(".krc") -> LyricFormat.KRC
            name.endsWith(".qrc") -> LyricFormat.QRC
            name.endsWith(".lrcx") -> LyricFormat.LRCX
            name.endsWith(".alm3ll") -> LyricFormat.LRC
            else -> recipe.sources.firstOrNull()?.format
        }
    }

    private fun resolveDir(context: Context, src: LocalSource): File? {
        val base = when (src.base) {
            BaseDir.EXTERNAL_FILES -> context.getExternalFilesDir(null)
            BaseDir.EXTERNAL_CACHE -> context.externalCacheDir
            BaseDir.FILES -> context.filesDir
            BaseDir.CACHE -> context.cacheDir
        } ?: return null
        return File(base, src.subPath)
    }

    private fun matchesExt(file: File, ext: List<String>): Boolean {
        if (ext.isEmpty()) return true
        val name = file.name.lowercase()
        return ext.any { name.endsWith(".$it") }
    }

    /** 去掉「歌名-hash」里的 hash 尾巴 */
    private fun stripHash(stem: String): String = HASH_SUFFIX.replace(stem, "")

    /**
     * 歌名核心词：先砍掉括号里的版本说明 / 副标题，再归一化。
     * 「此生不换 (记忆是条长线)」→「此生不换」，「情网2026(DJ咚鼓版)」→「情网2026」。
     */
    fun titleCore(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var cut = text.indexOfFirst {
            it == '(' || it == '（' || it == '[' || it == '【' || it == '《' ||
                it == '_' || it == '-' || it == '–' || it == '—'
        }
        if (cut < 0) cut = text.length
        val head = if (cut > 1) text.substring(0, cut) else text
        val core = normalize(head)
        return if (core.isNotEmpty()) core else normalize(text)
    }

    /** 两个歌名是否指向同一首歌：核心词相等或互相包含 */
    fun titlesMatch(a: String?, b: String?): Boolean {
        val x = titleCore(a)
        val y = titleCore(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    /**
     * 兜底认领前的身份校验：歌词自带的 id / 歌名 / 歌手只要与当前歌曲**明确冲突**就不认领。
     * 「按总时长猜歌」最容易在这里翻车——实测酷狗把 3 分 23 秒的《怎叹》认成了
     * 3 分 28 秒的《此生不换》，网易云把另一首歌的缓存认成了当前歌曲。
     */
    private fun identityConflicts(
        lyric: LocalLyric,
        wantCore: String,
        wantArtist: String,
        mediaId: String
    ): Boolean {
        val embeddedId = normalize(lyric.id)
        if (embeddedId.isNotEmpty() && mediaId.isNotEmpty() && embeddedId != mediaId) return true
        val embeddedCore = titleCore(lyric.title)
        if (embeddedCore.isNotEmpty() && wantCore.isNotEmpty() &&
            !(embeddedCore == wantCore || embeddedCore.contains(wantCore) || wantCore.contains(embeddedCore))
        ) return true
        val embeddedArtist = normalize(lyric.artist)
        if (embeddedArtist.isNotEmpty() && wantArtist.isNotEmpty() &&
            !(embeddedArtist.contains(wantArtist) || wantArtist.contains(embeddedArtist))
        ) return true
        return false
    }

    /** 归一化：只保留字母/数字/中日韩文字，忽略大小写与符号 */
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }
}
