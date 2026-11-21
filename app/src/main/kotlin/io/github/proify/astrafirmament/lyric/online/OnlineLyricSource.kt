package io.github.proify.astrafirmament.lyric.online

import android.content.Context
import io.github.proify.astrafirmament.lyric.LocalLyricFinder
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * 在线歌词统一入口。
 *
 * 调用方在后台线程同步调用 [search]，拿到 [LyricPayload] 后把 `payload.lines`
 * 直接塞进 Song 推送即可（译文/音译/逐字都已贴好）：
 * ```
 * val payload = OnlineLyricSource.search(title, artist, durationMs)
 * payload?.let { publish(it.lines, it.source) }
 * ```
 *
 * 策略：落盘缓存 → [PROVIDERS] **并行**跑，谁先给出一个通过打分的候选就用谁，
 * 不等其它源。单源上限 [SOURCE_TIMEOUT_MS]，整体上限 [TOTAL_BUDGET_MS]。
 * 任何失败/无候选都只返回 null，从不抛异常。
 *
 * 负缓存只在「所有源都明确答复没有」时才写：某个源超时/503 说明这次没验明，
 * 写下去等于把这首歌锁死半小时（v1 时代的负缓存就是被这样写坏的）。
 */
internal object OnlineLyricSource {

    /** 一次 search 的总预算：到点就返回，免得拖住播放 */
    private const val TOTAL_BUDGET_MS = 6_000L

    /** 单个源的上限：某个源卡住时不能连累其它源 */
    private const val SOURCE_TIMEOUT_MS = 2_500L

    /** 缓存 key 里的时长取整到秒：同一首歌的不同版本靠它区分 */
    private const val DURATION_KEY_DIVISOR = 1_000L

    /** 源的优先级顺序：只是并发启动顺序，命中仍以「谁先回」为准 */
    private val PROVIDERS: List<OnlineProvider> = listOf(
        NeteaseOnlineProvider,
        QQMusicProvider,
        KuwoProvider,
        KugouProvider,
        LrclibProvider
    )

    /**
     * 共享线程池：每次 search 都要并发 4 个源，用完即 throw 会频繁创建线程。
     *
     * 被放弃的请求不用强杀——它们自带 [SOURCE_TIMEOUT_MS] 的读写超时，会自己结束。
     */
    private val executor = Executors.newCachedThreadPool(ThreadFactory { runnable ->
        Thread(runnable, "xinghe-lyric-fetch").apply { isDaemon = true }
    })

    /** 注入 app Context 后才会落盘缓存；不注入也能用，只是每次都要联网 */
    fun install(context: Context) {
        OnlineLyricCache.install(context)
    }

    /**
     * 宿主进程里没人主动 [install] 时，借 ActivityThread 拿到宿主 Application。
     *
     * 缓存目录必须落在**宿主自己**的 cacheDir（模块进程无权限写宿主的私有目录），
     * 所以这里只认运行时的宿主 Application，不退回模块自己的 Context。
     */
    private fun ensureCache() {
        if (OnlineLyricCache.isInstalled()) return
        runCatching {
            val thread = Class.forName("android.app.ActivityThread")
            val app = thread.getMethod("currentApplication").invoke(null) as? Context
            if (app != null) OnlineLyricCache.install(app)
        }
    }

    /**
     * 先缓存后网络；命中返回已贴好 translation/roma 的 [LyricPayload]，未命中返回 null。
     *
     * 同步阻塞，最坏约 [TOTAL_BUDGET_MS] 毫秒，**必须**在后台线程调用。
     */
    fun search(
        title: String,
        artist: String?,
        durationMs: Long,
        log: ((String) -> Unit)? = null
    ): LyricPayload? = searchChecked(title, artist, durationMs, log).payload

    /**
     * 与 [search] 同一条链路，但额外告诉调用方「这次是不是已经验明没有歌词」。
     *
     * Spotify 侧自己还有一层负缓存，它必须只在这份结论为真时写：某个源超时或随机拒答
     * 说明这次没验明，写下去等于把这首歌锁死半小时。
     */
    fun searchChecked(
        title: String,
        artist: String?,
        durationMs: Long,
        log: ((String) -> Unit)? = null
    ): SearchOutcome = runCatching {
        val emit: (String) -> Unit = { message -> log?.invoke(message) }
        val name = title.trim()
        if (name.isEmpty()) return@runCatching SearchOutcome(null, false)
        ensureCache()
        val key = cacheKey(name, artist, durationMs)
        val cached = OnlineLyricCache.read(key, emit)
        if (cached != null) {
            if (cached.missing) {
                emit("负缓存记录此歌无歌词（" + cached.reason.orEmpty() + "）")
                return@runCatching SearchOutcome(null, true)
            }
            cached.payload?.let { return@runCatching SearchOutcome(it, false) }
        }
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS
        emit("在线源并行查询：" + PROVIDERS.joinToString("/") { it.name })
        val verdict = race(MatchQuery(name, artist, durationMs), deadline, key, emit)
        val payload = verdict.payload
        if (payload != null) {
            OnlineLyricCache.write(key, payload, emit)
            return@runCatching SearchOutcome(payload, false)
        }
        SearchOutcome(null, verdict.confirmedMissing)
    }.getOrDefault(SearchOutcome(null, false))

    /**
     * 并发跑所有源，第一个有效结果立刻返回。
     *
     * 循环结束（全部源都有答复，或总预算耗尽）后决定要不要写负缓存：
     * 只有「每个源都回了且没有一个发生过传输失败」才算确认无歌词。
     */
    private fun race(
        query: MatchQuery,
        deadline: Long,
        key: String,
        emit: (String) -> Unit
    ): Verdict {
        val queue = LinkedBlockingQueue<Outcome>()
        for (provider in PROVIDERS) {
            executor.execute {
                runCatching { queue.put(lookup(provider, query, deadline, emit)) }
            }
        }
        val answers = ArrayList<Outcome>(PROVIDERS.size)
        while (answers.size < PROVIDERS.size) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0L) {
                emit("总预算用尽，" + (PROVIDERS.size - answers.size) + " 个源未回")
                break
            }
            val outcome = runCatching {
                queue.poll(left, TimeUnit.MILLISECONDS)
            }.getOrNull() ?: break
            answers.add(outcome)
            outcome.payload?.let { payload ->
                emit("采用 " + outcome.provider + " 的结果（" + payload.lines.size + " 行）")
                return Verdict(payload, false)
            }
        }
        return Verdict(null, decideMissing(key, answers, emit))
    }

    /** 跑单个源，记录耗时与是否发生传输失败 */
    private fun lookup(
        provider: OnlineProvider,
        query: MatchQuery,
        deadline: Long,
        emit: (String) -> Unit
    ): Outcome {
        val started = System.currentTimeMillis()
        val sourceDeadline = minOf(deadline, started + SOURCE_TIMEOUT_MS)
        OnlineHttp.resetFailure()
        val payload = runCatching {
            provider.fetch(query, sourceDeadline, emit)
        }.getOrNull()
        val cost = System.currentTimeMillis() - started
        val failed = OnlineHttp.hadFailure()
        val tail = if (failed) "（有传输失败）" else ""
        val what = payload?.let { "命中 " + it.lines.size + " 行" } ?: "无结果"
        emit(provider.name + "：" + what + "，耗时 " + cost + "ms" + tail)
        return Outcome(provider.name, payload, failed)
    }

    /**
     * 全部源都明确答复「没有」才写负缓存；否则这次没验明，不写。
     *
     * @return true 表示「已确认无词」，调用方据此决定自己那层负缓存要不要落盘
     */
    private fun decideMissing(key: String, answers: List<Outcome>, emit: (String) -> Unit): Boolean {
        if (answers.size < PROVIDERS.size) {
            emit("负缓存不写：只有 " + answers.size + "/" + PROVIDERS.size + " 个源给出答复")
            return false
        }
        val failed = answers.count { it.failed }
        if (failed > 0) {
            emit("负缓存不写：" + failed + " 个源传输失败，没验明")
            return false
        }
        OnlineLyricCache.writeMissing(key, PROVIDERS.size.toString() + " 源均无匹配", emit)
        return true
    }

    /** 一次源查询的结果：[failed] 表示传输层出过错（此时不能据此判定无歌词） */
    private class Outcome(val provider: String, val payload: LyricPayload?, val failed: Boolean)

    /** race 的结论：payload 为空时，[confirmedMissing] 说明是否已验明「确实没有」 */
    private class Verdict(val payload: LyricPayload?, val confirmedMissing: Boolean)

    /** 归一化歌名/歌手 + 时长秒数做 key，歌名里的标点差异不会各存一份 */
    private fun cacheKey(title: String, artist: String?, durationMs: Long): String {
        val bucket = if (durationMs > 0L) durationMs / DURATION_KEY_DIVISOR else 0L
        return listOf(
            LocalLyricFinder.normalize(title),
            LocalLyricFinder.normalize(artist),
            bucket.toString()
        ).joinToString("|")
    }
}

/**
 * 一次在线查词的结论。
 *
 * [confirmedMissing] 只在一种情况下为真：所有源都给出答复、没有任何传输失败、且确实
 * 没有通过打分的候选。调用方（Spotify 侧）只在这时写自己的负缓存——源临时抽风被记成
 * 「这首歌没有歌词」，正是 v1 命中率上不去的元凶之一。
 */
internal class SearchOutcome(val payload: LyricPayload?, val confirmedMissing: Boolean)
