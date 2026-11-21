package io.github.proify.astrafirmament.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import io.github.proify.astrafirmament.capability.link.DevLog
import io.github.proify.astrafirmament.capability.link.LinkHub
import io.github.proify.astrafirmament.hook.ApkIntegrityGuard
import io.github.proify.astrafirmament.hook.LinkHook
import io.github.proify.astrafirmament.settings.ModulePrefs
import io.github.proify.astrafirmament.shared.SuReader
import java.io.File

/**
 * 智能文件「落地」环节（App 进程，有 KernelSU root）：
 * system 发现候选 → su stat 拿权威大小/时间 → su cp 到私有目录 → 完整性校验 → 请 system 出卡。
 *
 * 为什么必须在本进程做：system_server(uid 1000) 读不到 /storage/emulated/0（实测
 * `su 1000 -c head/ls` 均 Permission denied），而这里 `su -c` 是 uid 0，既能复制也能删原包。
 */
internal object SmartApkIntake {
    /** 这些拒绝原因只说明「文件还没写完」，等一会儿再看有意义 */
    private val RETRYABLE_REJECTS = setOf(
        ApkIntegrityGuard.Reject.EMPTY,
        ApkIntegrityGuard.Reject.TOO_SMALL,
        ApkIntegrityGuard.Reject.SIZE_MISMATCH,
        ApkIntegrityGuard.Reject.TRUNCATED
    )

    private const val STAT_TIMEOUT_MS = 5_000L
    private const val COPY_TIMEOUT_MS = 120_000L

    /** 等「写完」的轮询间隔与总预算（接收器有超时，绝不能在这里长时间阻塞） */
    private const val SETTLE_POLL_MS = 1_200L
    private const val SETTLE_MAX_MS = 5_000L

    /** 复制+校验的轮数：第一轮失败多半是文件还在追加写，隔一会儿用新的权威大小再来一次 */
    private const val COPY_ATTEMPTS = 2
    private const val RETRY_GAP_MS = 1_500L
    private const val FREE_SPACE_RESERVE = 32L * 1024L * 1024L

    /** 未来时间戳容忍窗口：超过就当时钟/时区不可信（原 10min 太宽） */
    private const val FUTURE_SKEW_MS = 2 * 60 * 1_000L

    /** 复制前算原文件内容指纹的超时：一次 su 读 512KB，8s 足够 */
    private const val CONTENT_KEY_TIMEOUT_MS = 8_000L

    /**
     * 出卡只认「刚下载完」的文件：mtime 超过这个窗口就说明是旧文件被移动/重扫
     * （移动文件会刷新媒体库记录但不动 mtime）。没有这道闸门，媒体库一重扫
     * 就会把用户早就下好的包当成新下载重新弹一张卡（5.10 的真机反馈）。
     */
    private const val CARD_MAX_AGE_MS = 15L * 60L * 1_000L

    /** 删除结果要区分「已删掉」和「连原包在哪都不知道」，否则提示语会撒谎 */
    enum class DeleteResult { DELETED, UNKNOWN, FAILED }

    /**
     * 同一原始路径的整链互斥：媒体库轮询(20s)、第三方扫描(2.5s)、system 重投会同时打到同一个包。
     * 两条链路同时 [copyFromRoot] 时，后进来的 `copy.delete()` 会删掉前者正在写的那份，
     * 两边校验都拿不到预期大小 —— 白拷几百兆，极端情况下还会出两张卡。
     * 用固定条数的锁（按路径哈希取模）而不是 map：不会随路径数量无限增长。
     */
    private val pathLocks = Array(16) { Any() }

    private fun <T> withPathLock(path: String, block: () -> T): T =
        synchronized(pathLocks[Math.floorMod(path.hashCode(), pathLocks.size)]) { block() }

    /**
     * 落地结果。[RETRY] 只表示「文件还没写完」——system 投递靠它自己的有界重投收尾，
     * 第三方扫描靠 [ThirdPartyApkScanner] 的下一轮轮询收尾。
     */
    enum class Outcome { DONE, SKIPPED, RETRY, FAILED }

    fun enabled(context: Context): Boolean = runCatching {
        val prefs = ModulePrefs.of(context)
        ModulePrefs.isFileInstallerEnabled(prefs) && ModulePrefs.isSmartIslandEnabled(prefs)
    }.getOrDefault(false)

    fun run(context: Context, request: ApkBridge.ApkRequest) {
        execute(context, request, "智能文件落地异常", sizeVerified = false) {
            ApkBridge.isAllowedPath(it)
        }
    }

    /**
     * 第三方入口（QQ/TIM 落盘目录，见 [ThirdPartyApkScanner]）：这类文件不是 system
     * 投递来的，既没有一次性口令也没有重投可用，出卡改成由本进程直接做。除路径白名单与
     * 出卡方式外，「复制 → 校验 → 记录」全程复用 [intake]。
     *
     * [sizeVerified] = true 表示调用方（扫描器的 find）刚读过大小且已过稳定判定，
     * 不必再花 [SETTLE_MAX_MS] 轮询等写完。
     */
    fun runFromThirdParty(
        context: Context,
        request: ApkBridge.ApkRequest,
        sizeVerified: Boolean = false
    ): Outcome = execute(context, request, "第三方安装包落地异常", sizeVerified) {
        ThirdPartyApkScanner.isThirdPartyPath(it)
    }

    /** 两个入口共用的外壳：刷新日志开关、清旧副本、异常兜底成 [Outcome.FAILED] */
    private fun execute(
        context: Context,
        request: ApkBridge.ApkRequest,
        failureTag: String,
        sizeVerified: Boolean,
        allowed: (String) -> Boolean
    ): Outcome {
        DevLog.refresh(context)
        // 清理绝不能在投递路径上同步做：几百 MB 的目录扫描会拖慢「检测到就推送」
        ApkIncomingStore.pruneAsync(context)
        return runCatching { withPathLock(request.path) { intake(context, request, allowed, sizeVerified) } }
            .onFailure { DevLog.e("$failureTag：${request.path}", it) }
            .getOrDefault(Outcome.FAILED)
    }

    private fun intake(
        context: Context,
        request: ApkBridge.ApkRequest,
        allowed: (String) -> Boolean,
        sizeVerified: Boolean
    ): Outcome {
        val path = request.path
        val started = SystemClock.elapsedRealtime()
        // 入口就打点：报「不检测」时先看有没有走到这里；没这条就是发现链路的问题
        DevLog.i(
            "智能文件 stage=start source=${request.source} name=${request.name} " +
                "size=${request.size} age=${ageOf(request)} path=$path"
        )
        if (!allowed(path)) {
            DevLog.w("智能文件 stage=allowed 拒绝：路径不在白名单 path=$path")
            return Outcome.SKIPPED
        }
        val stamp = waitForCompletion(path, request.size, sizeVerified)
        if (stamp == null) {
            DevLog.w("智能文件 stage=stat 失败：无 root 或文件已删 path=$path root=${SuReader.available()}")
            requestAbort(context, path)
            return Outcome.FAILED
        }
        DevLog.i("智能文件 stage=stat ok：size=${stamp.size}B mtime=${stamp.modifiedAt}（期望 size=${request.size}）")
        if (!freshEnough(request, stamp.modifiedAt)) {
            DevLog.i("智能文件 stage=fresh 拒绝：候选不新鲜 path=$path")
            return Outcome.SKIPPED
        }
        val age = if (stamp.modifiedAt > 0L) System.currentTimeMillis() - stamp.modifiedAt else -1L
        if (tooOld(context, path, age, request.freshForMs)) return Outcome.SKIPPED
        if (ApkIncomingStore.alreadyDelivered(context, path, stamp.size, stamp.modifiedAt)) {
            DevLog.i("智能文件 stage=dedup 拒绝：同一份文件已出过卡 path=$path")
            return Outcome.SKIPPED
        }

        // ---- 复制前按内容去重：原文件大小 + 首尾各 256KB 的 MD5 ----
        // 同一份包从不同路径下载时，先算指纹就能直接命中已有副本，不必再复制几百兆。
        val sourceKey = ApkScanShell.sourceContentKey(path, CONTENT_KEY_TIMEOUT_MS)
        if (sourceKey != null && ApkIncomingStore.alreadyShown(context, sourceKey)) {
            DevLog.i("智能文件 stage=shown 拒绝（复制前）：同一份内容已弹过岛 path=$path")
            requestAbort(context, path)
            return Outcome.SKIPPED
        }
        val reused = if (sourceKey != null) {
            ApkIncomingStore.copyForContent(context, sourceKey, stamp.size)
        } else {
            null
        }
        // 命中已有副本也要过一遍完整性校验：坏了就丢掉重新复制，绝不把坏包递出去
        var reusableName = reused
        if (reused != null) {
            val reject = verifyCopy(context, reused, ApkIncomingStore.file(context, reused), stamp)
            if (reject != null) {
                ApkIncomingStore.forget(context, reused)
                reusableName = null
                DevLog.w("智能文件复用的副本没通过校验（${reject.name}），改为重新复制：$reused")
            }
        }
        val copyName: String
        val copy: File
        if (reusableName != null) {
            copyName = reusableName
            copy = ApkIncomingStore.file(context, reusableName)
            DevLog.i("智能文件 stage=reuse 命中已有副本，跳过复制：$copyName")
        } else {
            copyName = ApkIncomingStore.copyName(path)
            copy = ApkIncomingStore.file(context, copyName)
            if (!copyAndVerify(context, path, copy, copyName, stamp)) {
                // 不 abort：文件可能还在写，交给下一轮（system 有界重投 / 扫描器轮询）收尾
                DevLog.w("智能文件 stage=copy 未通过，等下一轮重投 path=$path")
                return Outcome.RETRY
            }
        }
        // 内容指纹算不出来（复制件读不开）时跳过内容去重，不要用一个假 key 把真包打成「已弹过」
        var contentKey = sourceKey
        if (contentKey == null) {
            contentKey = ApkIncomingStore.contentKey(copy, stamp.size)
            if (contentKey != null && ApkIncomingStore.alreadyShown(context, contentKey)) {
                DevLog.i("智能文件 stage=shown 拒绝：同一份内容已弹过岛 path=$path")
                requestAbort(context, path)
                return Outcome.SKIPPED
            }
        }
        ApkIncomingStore.record(context, copyName, path)
        if (contentKey != null) ApkIncomingStore.recordContent(context, contentKey, copyName)
        // 只清超配额的老副本；刚记录的这份在「最近访问」保护窗口内，不会被删
        ApkIncomingStore.prune(context, copyName)
        // 去重戳只在「出卡请求真的交出去了」之后才写：写早了而卡没弹出来，
        // 这个包 30 分钟内不再重投、24h 内不再弹，用户只会看到「下载了却不上岛」且日志还写「已出卡」
        val shown = askCard(context, request, copyName, stamp)
        if (!shown) {
            DevLog.w("智能文件 stage=card 未能出卡（开关关闭或广播未送达），不写去重戳，留待下一轮 path=$path")
            return Outcome.RETRY
        }
        ApkIncomingStore.markDelivered(context, path, stamp.size, stamp.modifiedAt)
        if (contentKey != null) ApkIncomingStore.markShown(context, contentKey)
        // 交付完成后再在后台收一次：把这条路径上被新副本替下来的旧包清掉
        ApkIncomingStore.pruneAsync(context)
        DevLog.i("智能文件 stage=card 已出卡：name=$copyName cost=${SystemClock.elapsedRealtime() - started}ms")
        return Outcome.DONE
    }

    /** 候选年龄描述：入口日志统一用它，避免每个调用点各写一遍 */
    private fun ageOf(request: ApkBridge.ApkRequest): String =
        if (request.modifiedAt > 0L) "${(System.currentTimeMillis() - request.modifiedAt) / 1000}s"
        else "unknown"

    private class FileStamp(val size: Long, val modifiedAt: Long)

    /**
     * 「不是新下载」闸门（[CARD_MAX_AGE_MS]）：mtime 太老的包不出卡——移动/重命名会刷新
     * 媒体库记录却不动 mtime，没有这道闸门，媒体库一重扫就把旧包当新下载再弹一次。
     */
    private fun tooOld(context: Context, path: String, age: Long, freshForMs: Long): Boolean {
        // freshForMs <= 0 = 调用方已用「基线差集」证明这是新增文件（扫描器），不需要年龄闸门；
        // 补扫必须能推出解锁前下载的包，否则息屏漏检永远修不掉
        if (freshForMs <= 0L) return false
        if (age < 0L || age <= CARD_MAX_AGE_MS) return false
        DevLog.i("智能文件不是新下载（mtime 已 ${age / 1000}s），不再弹岛：$path")
        requestAbort(context, path)
        return true
    }

    /**
     * 等文件「写完」：以 su stat 的权威大小为准，连续两次读到同一大小（且与 DownloadManager
     * 给的 total 一致）才算落盘完毕。超时就把当前状态交回去，由复制后的结构校验决定是否
     * 等系统重投 —— 浏览器是「先建文件、再流式追加」，第一轮必然读到半截文件。
     */
    private fun waitForCompletion(path: String, expectedSize: Long, trusted: Boolean): FileStamp? {
        // 扫描器已经用 find 读过大小、且判定过「不再增长」，再轮询 5s 只是白白拖慢推送
        if (trusted && expectedSize > 0L) return statSource(path)
        val deadline = SystemClock.elapsedRealtime() + SETTLE_MAX_MS
        var last = -1L
        while (true) {
            val stamp = statSource(path) ?: return null
            val sizeOk = stamp.size > 0L && (expectedSize <= 0L || stamp.size == expectedSize)
            if (sizeOk && stamp.size == last) return stamp
            last = stamp.size
            if (SystemClock.elapsedRealtime() >= deadline) return stamp
            DevLog.i("智能文件等待写入完成：$path 当前 ${stamp.size}B")
            runCatching { Thread.sleep(SETTLE_POLL_MS) }
        }
    }

    /**
     * 复制 + 完整性校验，最多 [COPY_ATTEMPTS] 轮。只有结构性的「还没写完」才重试；
     * 其它拒绝原因（例如根本不是 APK）直接放弃，免得白白复制几百兆。
     */
    private fun copyAndVerify(
        context: Context,
        path: String,
        copy: File,
        copyName: String,
        first: FileStamp
    ): Boolean {
        var stamp = first
        repeat(COPY_ATTEMPTS) { round ->
            val reusable = copy.isFile && copy.length() == stamp.size &&
                ApkIncomingStore.originOf(context, copyName) == path
            if (!reusable && !copyFromRoot(path, copy, stamp.size)) {
                DevLog.w("智能文件复制失败（第 ${round + 1} 轮）：$path")
                return false
            }
            val reject = verifyCopy(context, copyName, copy, stamp)
            if (reject != null) DevLog.w("智能文件校验拒绝：${reject.name} size=${copy.length()}B")
            if (reject == null) return true
            ApkIncomingStore.forget(context, copyName)
            if (reject !in RETRYABLE_REJECTS) return false
            DevLog.i("智能文件副本还不完整（${reject.name}），稍后重试：$path")
            runCatching { Thread.sleep(RETRY_GAP_MS) }
            stamp = statSource(path) ?: return false
        }
        return false
    }

    /** 告诉 system「这条路径别再重投了」：文件已不存在，或反复校验不过 */
    private fun requestAbort(context: Context, path: String) {
        // QQ/TIM 目录不是 system 投递来的，那边没有这条路径的重投可停
        if (ThirdPartyApkScanner.isThirdPartyPath(path)) return
        runCatching {
            context.sendBroadcast(
                ApkBridge.abortIntent(context, context.packageName, path),
                LinkHook.PERMISSION_ISLAND_COMMAND,
            )
            DevLog.i("智能文件已请求停止重投：$path")
        }.onFailure { DevLog.w("智能文件停止重投广播失败：${it.message}") }
    }

    /** 权威大小与 mtime 只能问 su（App 自己 uid 读不了 /storage，stat 也一样） */
    private fun statSource(path: String): FileStamp? {
        val out = SuReader.read("stat -c '%s %Y' " + quote(path), STAT_TIMEOUT_MS) ?: return null
        val parts = out.trim().split(' ')
        if (parts.size < 2) return null
        val size = parts[0].toLongOrNull() ?: return null
        val seconds = parts[1].toLongOrNull() ?: return null
        return FileStamp(size, seconds * 1_000L)
    }

    /** 路径已在 [ApkBridge.isAllowedPath] 里拒绝过引号/换行，这里单引号包裹即可安全 */
    private fun quote(path: String): String = "'" + path + "'"

    private fun freshEnough(request: ApkBridge.ApkRequest, actualModifiedAt: Long): Boolean {
        if (request.freshForMs <= 0L) return true
        val stamp = if (actualModifiedAt > 0L) actualModifiedAt else request.modifiedAt
        if (stamp <= 0L) return false
        val age = System.currentTimeMillis() - stamp
        return age <= request.freshForMs && age >= -FUTURE_SKEW_MS
    }

    /**
     * 原子复制：先 `cp` 到 `xxx.part`，校验大小后再 rename 成正式副本名。
     * 这样复制超时/被杀只会留下 `.part`（[ApkIncomingStore.prune] 会清），
     * provider、去重和卡片都不会看到半截文件。
     */
    private fun copyFromRoot(path: String, copy: File, expected: Long): Boolean {
        val dir = copy.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        if (available(dir) < expected + FREE_SPACE_RESERVE) {
            DevLog.w("智能文件复制跳过：剩余空间不足以放下 ${expected}B")
            return false
        }
        val part = File(dir, copy.name + ApkIncomingStore.PART_SUFFIX)
        runCatching { part.delete() }
        runCatching { copy.delete() }
        // su cp 是 root 身份建的副本，属主会是 root：不 chown 回本进程 uid 的话，
        // 本进程连 openFileDescriptor 都打不开（真机表现为「副本未通过完整性校验：NOT_OPENABLE」）。
        val uid = android.os.Process.myUid()
        val cmd = "mkdir -p " + quote(dir.absolutePath) +
            " && cp -f " + quote(path) + " " + quote(part.absolutePath) +
            " && chown " + uid + " " + quote(part.absolutePath) +
            " && chmod 600 " + quote(part.absolutePath) +
            " && stat -c %s " + quote(part.absolutePath)
        val written = SuReader.read(cmd, COPY_TIMEOUT_MS)?.trim()?.toLongOrNull() ?: 0L
        if (written != expected || part.length() != expected) {
            DevLog.w("智能文件副本大小不符：期望 $expected 实际 $written")
            runCatching { part.delete() }
            return false
        }
        if (!part.renameTo(copy)) {
            // 同一目录内 rename 不应失败；万一失败用 su mv 兜一次（su 可能已超时退出）
            val moved = SuReader.read(
                "mv -f " + quote(part.absolutePath) + " " + quote(copy.absolutePath),
                STAT_TIMEOUT_MS
            ) != null
            if (!moved) {
                runCatching { part.delete() }
                DevLog.w("智能文件副本落名失败（rename/mv）：$copy")
                return false
            }
        }
        if (copy.length() != expected) {
            DevLog.w("智能文件副本大小不符（落名后）：期望 $expected 实际 ${copy.length()}")
            runCatching { copy.delete() }
            return false
        }
        return true
    }

    private fun available(dir: File): Long =
        runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)

    /**
     * 完整性校验复用 [ApkIntegrityGuard]：它按 uri 读文件，而这份 uri 指向本进程自己的
     * provider，读私有目录里的副本没有任何权限问题（system 那次失败是权限，不是逻辑）。
     */
    private fun verifyCopy(
        context: Context,
        copyName: String,
        copy: File,
        stamp: FileStamp
    ): ApkIntegrityGuard.Reject? {
        val uri = ApkBridge.uriFor(context, copyName)
        val result = ApkIntegrityGuard.verify(context, uri, stamp.size, copy.absolutePath, stamp.modifiedAt)
        if (result.ok) return null
        DevLog.w("智能文件副本未通过完整性校验：$copyName ${result.reject}")
        runCatching { copy.delete() }
        return result.reject
    }

    /**
     * 出卡：system 投递来的走广播（BAL / force-stop 只有 system 身份能穿）；第三方扫描来的
     * 没有一次性口令，广播会被 [LinkHook] 当伪造丢弃，所以由本进程直接出卡——按钮回调
     * （安装/查看/分享）依旧由 [LinkHub] 请 system 代发 startActivity，删除走本进程 su rm。
     */
    private fun askCard(
        context: Context,
        request: ApkBridge.ApkRequest,
        copyName: String,
        stamp: FileStamp
    ): Boolean {
        val display = displayName(request, copyName)
        val copy = ApkIncomingStore.file(context, copyName)
        val uri = ApkBridge.uriFor(
            context,
            copyName,
            originPath = request.path,
            packageName = packageNameOf(context, copy),
            size = stamp.size,
            modifiedAt = stamp.modifiedAt
        )
        val target = ApkBridge.target(display, uri)
        if (request.token.isBlank()) {
            // 开关关着时 showCardFromModule 会静默丢掉这次出卡：这种情况必须返回 false，
            // 否则会写下一个假的「已出卡」戳，用户打开开关后就再也等不到这张卡
            if (!LinkHub.canShow(context, LinkHook.KIND_FILE_OUT)) {
                DevLog.w("第三方安装包副本就绪但出卡被开关拦下：$display")
                return false
            }
            DevLog.i("第三方安装包副本就绪，由本进程直接出卡：$display")
            LinkHub.showCardFromModule(context, LinkHook.KIND_FILE_OUT, target)
            return true
        }
        val intent = Intent(LinkHook.ACTION_LAUNCH)
            .putExtra(LinkHook.EXTRA_KIND, ApkBridge.KIND_READY)
            .putExtra(LinkHook.EXTRA_TARGET, target)
            .putExtra(ApkBridge.EXTRA_TOKEN, request.token)
            .putExtra(ApkBridge.EXTRA_ORIGIN, request.path)
        val sent = runCatching {
            context.sendBroadcast(intent, LinkHook.PERMISSION_ISLAND_COMMAND)
        }.isSuccess
        DevLog.i("智能文件副本就绪，已请 system 出卡：$display sent=$sent")
        return sent
    }

    /** 卡片上要写包名：只能从副本本身解析（原始文件在 /storage，本进程读不了） */
    private fun packageNameOf(context: Context, copy: File): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageArchiveInfo(copy.absolutePath, 0)?.packageName
    }.getOrNull()

    /**
     * 卡片副标题用原文件名：QQ 存的 `xxx.apk.1` 也要显示原名（用户就按这个名字找包），
     * 只有真拿不到像样的名字时才退回副本名。
     */
    private fun displayName(request: ApkBridge.ApkRequest, copyName: String): String {
        val name = request.name
        if (name.isNotBlank() && ThirdPartyApkScanner.isPackageName(name)) return name
        return copyName.substringAfter('-')
    }

    /**
     * 「删除」必须删用户的原始安装包（副本只是中间产物）：system(1000) 删不了 /storage，
     * 只有这里 su rm 做得到。原包路径来自落盘映射，查不到就如实返回 UNKNOWN，不假报成功。
     */
    fun deleteOriginal(context: Context, target: String): DeleteResult {
        DevLog.refresh(context)
        val uri = runCatching {
            Uri.parse(target.substringAfter(ApkBridge.TARGET_SEPARATOR, ""))
        }.getOrNull()
        val copyName = uri?.lastPathSegment ?: return DeleteResult.UNKNOWN
        val origin = ApkIncomingStore.originOf(context, copyName)
        // 第三方目录里的 .apk.1 同样要能删（它是用户真正的安装包，副本只是中间产物）
        val allowed = origin != null &&
            (ApkBridge.isAllowedPath(origin) || ThirdPartyApkScanner.isThirdPartyPath(origin))
        val removed = if (allowed && origin != null) {
            SuReader.read("rm -f " + quote(origin), STAT_TIMEOUT_MS) != null
        } else {
            false
        }
        ApkIncomingStore.deleteCopy(context, copyName)
        ApkIncomingStore.forget(context, copyName)
        val result = when {
            removed -> DeleteResult.DELETED
            !allowed -> DeleteResult.UNKNOWN
            else -> DeleteResult.FAILED
        }
        DevLog.i("智能文件删除结果=$result origin=$origin")
        return result
    }
}
