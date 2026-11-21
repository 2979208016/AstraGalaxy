package io.github.proify.astrafirmament.app

import android.content.Context
import android.content.SharedPreferences
import io.github.proify.astrafirmament.capability.link.DevLog
import io.github.proify.astrafirmament.settings.ModulePrefs
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `files/apk-incoming/` 副本仓库：命名、内容指纹、去重戳、配额清理。
 *
 * 存储治理要点（原实现会无限涨，实测 222MB / 8 个副本）：
 * 1. **复制前按内容去重**：[copyForContent] 用 `size + 首尾各 256KB 的 MD5` 找已有副本，
 *    同一个安装包从不同路径下载只保留一份。
 * 2. **配额硬上限**：总量 [DEFAULT_QUOTA_BYTES]（可配 `quota_bytes`）+ 文件数上限
 *    [PRUNE_KEEP_FILES]（可配 `quota_files`）；`apk_clean_days <= 0` 只关掉「按天清」，
 *    数量/容量上限照样生效。
 * 3. **正在投递的文件不删**：[PROTECT_MS] 内被访问过的副本跳过清理。
 * 4. **prefs 也要清理**：`delivered:` / `delivered_at:` / `shown_content:` 按 TTL 删，
 *    整个 xml 控制在 [PREFS_BUDGET_BYTES] 内，超了按最旧淘汰。
 * 5. 副本先写 `*.part` 再 rename（[partFile]），中断留下的 `.part` 会被清掉，
 *    [usage] 与 provider 都不把 `.part` 当成品。
 */
internal object ApkIncomingStore {
    const val PREFS = "xinghe_apk_incoming"

    private const val KEY_ORIGIN = "origin:"
    private const val KEY_DISPLAY = "display:"
    private const val KEY_DELIVERED = "delivered:"
    private const val KEY_DELIVERED_AT = "delivered_at:"
    private const val KEY_SHOWN = "shown_content:"

    /** 内容指纹 → 副本名：命中就直接复用，不再复制 */
    private const val KEY_CONTENT = "copy_content:"

    /** 副本最近一次被使用的时间（保护窗口用） */
    private const val KEY_TOUCH = "touch:"

    private const val KEY_QUOTA_BYTES = "quota_bytes"
    private const val KEY_QUOTA_FILES = "quota_files"

    /** 同一来源路径的抑制窗口：30 分钟内不重复出卡 */
    private const val DELIVERED_SUPPRESS_MS = 30L * 60L * 1_000L

    /** 同一来源路径的去重戳保留多久（原实现只写不删，实测 86 个键持续增长） */
    private const val DELIVERED_TTL_MS = 24L * 60L * 60L * 1_000L

    /** 同一内容弹过岛就不弹的窗口 */
    private const val SHOWN_TTL_MS = 24L * 60L * 60L * 1_000L

    /** 副本目录总量上限（默认 200MB，可在同一 prefs 里用 `quota_bytes` 覆盖） */
    private const val DEFAULT_QUOTA_BYTES = 200L * 1024L * 1024L

    /** 副本数量上限（默认 8 份） */
    private const val PRUNE_KEEP_FILES = 8

    /** 最近被访问过的副本不进清理（正在投递/刚交付） */
    private const val PROTECT_MS = 10L * 60L * 1_000L

    /** 内容指纹分块大小，必须与 [ApkScanShell.sourceContentKey] 对齐 */
    private const val CONTENT_KEY_CHUNK = 256 * 1024

    /** 复用已有副本的最长年龄：太老的可能被手动删改过 */
    private const val REUSE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L

    const val PART_SUFFIX = ".part"

    /** 中断的 .part 超过这个时间就清掉 */
    private const val PART_STALE_MS = 60L * 60L * 1_000L

    /** prefs 体积上限：超了按最旧淘汰（原来只写不删会一直长） */
    private const val PREFS_BUDGET_BYTES = 8L * 1024L

    private const val NAME_LIMIT = 60
    private const val THREAD_NAME = "xinghe-apk-prune"

    private val pruneExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }
    private val pruneQueued = AtomicBoolean(false)

    // ---------------------------------------------------------------------
    // 目录与命名
    // ---------------------------------------------------------------------

    fun dir(context: Context): File = File(context.filesDir, ApkBridge.DIR_NAME)

    fun file(context: Context, copyName: String): File = File(dir(context), copyName)

    fun partFile(context: Context, copyName: String): File = File(dir(context), copyName + PART_SUFFIX)

    /** 副本名 = 来源路径摘要 + 安装名：同一来源路径固定，跨内容不互相覆盖 */
    fun copyName(originPath: String): String =
        digest(originPath) + "-" + installName(originPath)

    // ---------------------------------------------------------------------
    // 内容指纹 / 去重
    // ---------------------------------------------------------------------

    /**
     * 内容指纹 `size:md5(首 256KB || 尾 256KB)`，与 [ApkScanShell.sourceContentKey]
     * 完全一致，所以「复制前算原文件」和「复制后算副本」得到同一个键。
     */
    fun contentKey(copy: File, size: Long): String? {
        val length = if (copy.isFile) copy.length() else 0L
        if (length <= 0L) return null
        if (size > 0L && size != length) return null
        return runCatching { digestOf(copy, length) }.getOrNull()
    }

    /** 大小已由调用方校验过，这里只算首尾两块的 MD5 */
    private fun digestOf(copy: File, length: Long): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(copy).use { input ->
            val buffer = ByteArray(CONTENT_KEY_CHUNK)
            var remaining = minOf(CONTENT_KEY_CHUNK.toLong(), length)
            while (remaining > 0L) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = input.read(buffer, 0, want)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        if (length > CONTENT_KEY_CHUNK) {
            RandomAccessFile(copy, "r").use { raf ->
                raf.seek(length - CONTENT_KEY_CHUNK)
                val buffer = ByteArray(CONTENT_KEY_CHUNK)
                var read = 0
                while (read < buffer.size) {
                    val count = raf.read(buffer, read, buffer.size - read)
                    if (count <= 0) break
                    read += count
                }
                digest.update(buffer, 0, read)
            }
        }
        return length.toString() + ":" + hex(digest.digest())
    }

    /** 已有副本命中就返回副本名（调用方直接复用，不再复制）；命中时会刷新访问时间 */
    fun copyForContent(context: Context, contentKey: String, expectedSize: Long): String? {
        val store = prefs(context)
        val copyName = store.getString(KEY_CONTENT + contentKey, null) ?: return null
        val copy = file(context, copyName)
        if (!copy.isFile) {
            store.edit().remove(KEY_CONTENT + contentKey).apply()
            return null
        }
        if (expectedSize > 0L && copy.length() != expectedSize) return null
        if (System.currentTimeMillis() - copy.lastModified() > REUSE_MAX_AGE_MS) return null
        touch(context, copyName)
        return copyName
    }

    /** 记录「这个内容现在是这份副本」；同一内容换了副本时顺手回收旧副本 */
    fun recordContent(context: Context, contentKey: String, copyName: String) {
        val store = prefs(context)
        val previous = store.getString(KEY_CONTENT + contentKey, null)
        val editor = store.edit().putString(KEY_CONTENT + contentKey, copyName)
        if (previous != null && previous != copyName) {
            val old = file(context, previous)
            val lastUsed = maxOf(old.lastModified(), store.getLong(KEY_TOUCH + previous, 0L))
            if (old.isFile && System.currentTimeMillis() - lastUsed >= PROTECT_MS) {
                deleteCopy(context, previous)
                forget(context, previous)
            }
        }
        editor.apply()
    }

    /** 标记副本刚被使用过（保护窗口与清理排序都用它） */
    fun touch(context: Context, copyName: String) {
        runCatching {
            prefs(context).edit().putLong(KEY_TOUCH + copyName, System.currentTimeMillis()).apply()
        }
    }

    // ---------------------------------------------------------------------
    // 去重戳
    // ---------------------------------------------------------------------

    fun alreadyDelivered(context: Context, originPath: String, size: Long, modifiedAt: Long): Boolean {
        val store = prefs(context)
        val stamp = store.getString(KEY_DELIVERED + originPath, null) ?: return false
        val at = store.getLong(KEY_DELIVERED_AT + originPath, 0L)
        if (at <= 0L || System.currentTimeMillis() - at > DELIVERED_SUPPRESS_MS) return false
        return stamp == fingerprintOf(size, modifiedAt)
    }

    fun markDelivered(context: Context, originPath: String, size: Long, modifiedAt: Long) {
        runCatching {
            prefs(context).edit()
                .putString(KEY_DELIVERED + originPath, fingerprintOf(size, modifiedAt))
                .putLong(KEY_DELIVERED_AT + originPath, System.currentTimeMillis())
                .apply()
        }
    }

    fun alreadyShown(context: Context, contentKey: String): Boolean {
        val at = prefs(context).getLong(KEY_SHOWN + contentKey, 0L)
        return at > 0L && System.currentTimeMillis() - at < SHOWN_TTL_MS
    }

    fun markShown(context: Context, contentKey: String) {
        runCatching {
            prefs(context).edit().putLong(KEY_SHOWN + contentKey, System.currentTimeMillis()).apply()
        }
    }

    // ---------------------------------------------------------------------
    // 元数据
    // ---------------------------------------------------------------------

    fun record(context: Context, copyName: String, originPath: String) {
        runCatching {
            prefs(context).edit()
                .putString(KEY_ORIGIN + copyName, originPath)
                .putString(KEY_DISPLAY + copyName, installName(originPath))
                .putLong(KEY_TOUCH + copyName, System.currentTimeMillis())
                .apply()
        }
    }

    fun originOf(context: Context, copyName: String): String? =
        prefs(context).getString(KEY_ORIGIN + copyName, null)

    fun displayName(context: Context, copyName: String): String =
        prefs(context).getString(KEY_DISPLAY + copyName, null) ?: installName(copyName)

    fun deleteCopy(context: Context, copyName: String): Boolean {
        var deleted = false
        runCatching {
            deleted = file(context, copyName).delete() || deleted
            partFile(context, copyName).delete()
        }
        return deleted
    }

    /** 删除一份副本的全部记录（文件由 [deleteCopy] 负责） */
    fun forget(context: Context, copyName: String) {
        runCatching {
            val store = prefs(context)
            val editor = store.edit()
                .remove(KEY_ORIGIN + copyName)
                .remove(KEY_DISPLAY + copyName)
                .remove(KEY_TOUCH + copyName)
            // 反向的「内容 → 副本」也要一起清，否则会指向不存在的文件
            for ((key, value) in store.all) {
                if (key.startsWith(KEY_CONTENT) && value is String && value == copyName) editor.remove(key)
            }
            editor.apply()
            partFile(context, copyName).delete()
        }
    }

    // ---------------------------------------------------------------------
    // 清理
    // ---------------------------------------------------------------------

    /** 后台清理：启动时、交付后调用；重复请求会合并成一次 */
    fun pruneAsync(context: Context) {
        val app = context.applicationContext
        if (!pruneQueued.compareAndSet(false, true)) return
        pruneExecutor.execute {
            try {
                prune(app)
            } catch (error: Throwable) {
                DevLog.w("APK 副本清理失败：${error.message}")
            } finally {
                pruneQueued.set(false)
            }
        }
    }

    /**
     * 同步清理：`.part` 残留、天数过期、数量/容量上限、prefs 过期键。
     * [keep] 是刚交付、绝对不能被清掉的那一份。
     */
    fun prune(context: Context, keep: String? = null) {
        val folder = dir(context)
        val store = prefs(context)
        val now = System.currentTimeMillis()
        val files = runCatching { folder.listFiles()?.filter { it.isFile } ?: emptyList() }.getOrDefault(emptyList())

        // 1) 复制中断留下的 .part
        for (candidate in files) {
            if (!candidate.name.endsWith(PART_SUFFIX)) continue
            if (now - candidate.lastModified() > PART_STALE_MS) candidate.delete()
        }

        val copies = files.filter { !it.name.endsWith(PART_SUFFIX) }
        fun lastUsed(file: File): Long =
            maxOf(file.lastModified(), store.getLong(KEY_TOUCH + file.name, 0L))

        fun isProtected(file: File): Boolean =
            file.name == keep || now - lastUsed(file) < PROTECT_MS

        // 2) 按天清理（apk_clean_days <= 0 只关掉这一条）
        val days = ModulePrefs.apkCleanDays(ModulePrefs.of(context))
        if (days > 0) {
            val cutoff = now - days * 24L * 60L * 60L * 1_000L
            for (copy in copies) {
                if (isProtected(copy)) continue
                if (lastUsed(copy) < cutoff) {
                    deleteCopy(context, copy.name)
                    forget(context, copy.name)
                }
            }
        }

        // 3) 数量 + 容量硬上限：按最近使用时间从旧到新删
        val alive = copies.filter { it.isFile }.sortedBy { lastUsed(it) }
        var count = alive.size
        var total = alive.sumOf { it.length() }
        val maxFiles = maxOf(1, store.getInt(KEY_QUOTA_FILES, PRUNE_KEEP_FILES))
        val maxBytes = maxOf(1L, store.getLong(KEY_QUOTA_BYTES, DEFAULT_QUOTA_BYTES))
        for (copy in alive) {
            if (count <= maxFiles && total <= maxBytes) break
            if (isProtected(copy)) continue
            val length = copy.length()
            if (deleteCopy(context, copy.name)) {
                forget(context, copy.name)
                count--
                total -= length
            }
        }

        // 4) prefs：TTL + 孤儿键 + 体积预算
        prunePrefs(context, store, now)
    }

    private fun prunePrefs(context: Context, store: SharedPreferences, now: Long) {
        val existing = runCatching { dir(context).list()?.toHashSet() ?: HashSet<String>() }
            .getOrDefault(HashSet())
        val editor = store.edit()
        val removed = HashSet<String>()
        val all = store.all

        for ((key, value) in all) {
            if (key.startsWith(KEY_QUOTA_BYTES) || key.startsWith(KEY_QUOTA_FILES)) continue
            if (key.startsWith(KEY_DELIVERED_AT)) {
                if (value is Long && now - value > DELIVERED_TTL_MS) {
                    editor.remove(key)
                    editor.remove(KEY_DELIVERED + key.removePrefix(KEY_DELIVERED_AT))
                    removed.add(key)
                }
                continue
            }
            if (key.startsWith(KEY_DELIVERED)) {
                val origin = key.removePrefix(KEY_DELIVERED)
                if (!all.containsKey(KEY_DELIVERED_AT + origin)) {
                    editor.remove(key)
                    removed.add(key)
                }
                continue
            }
            if (key.startsWith(KEY_SHOWN)) {
                if (value is Long && now - value > SHOWN_TTL_MS) {
                    editor.remove(key)
                    removed.add(key)
                }
                continue
            }
            // 元数据键：键名或值指向的副本已经不在了就是孤儿（不认识的前缀一律不碰）
            val isMeta = key.startsWith(KEY_ORIGIN) || key.startsWith(KEY_DISPLAY) ||
                key.startsWith(KEY_TOUCH) || key.startsWith(KEY_CONTENT)
            if (!isMeta) continue
            val copyName = if (key.startsWith(KEY_CONTENT)) value as? String else key.substringAfter(':')
            if (copyName == null || copyName !in existing) {
                editor.remove(key)
                removed.add(key)
            }
        }

        // 体积预算：超过就从最旧（没有时间戳的排最前）开始淘汰
        var used = 0L
        val aged = ArrayList<Pair<String, Long>>()
        for ((key, value) in all) {
            if (key in removed) continue
            if (key.startsWith(KEY_QUOTA_BYTES) || key.startsWith(KEY_QUOTA_FILES)) continue
            used += key.length + 12L + value.toString().length
            aged.add(key to ageOf(key, value))
        }
        if (used > PREFS_BUDGET_BYTES) {
            aged.sortBy { it.second }
            for ((key, _) in aged) {
                if (used <= PREFS_BUDGET_BYTES) break
                editor.remove(key)
                used -= key.length + 12L + (all[key]?.toString()?.length ?: 0)
            }
        }
        runCatching { editor.apply() }
    }

    private fun ageOf(key: String, value: Any?): Long = when {
        key.startsWith(KEY_DELIVERED_AT) || key.startsWith(KEY_SHOWN) -> (value as? Long) ?: 0L
        key.startsWith(KEY_TOUCH) -> (value as? Long) ?: 0L
        else -> 0L
    }

    // ---------------------------------------------------------------------
    // 统计 / 清空
    // ---------------------------------------------------------------------

    /** 返回 (份数, 字节数)：不把 `.part` 算进成品 */
    fun usage(context: Context): Pair<Int, Long> {
        val files = runCatching {
            dir(context).listFiles()?.filter { it.isFile && !it.name.endsWith(PART_SUFFIX) } ?: emptyList()
        }.getOrDefault(emptyList())
        return files.size to files.sumOf { it.length() }
    }

    fun clearAll(context: Context): Int {
        var deleted = 0
        runCatching {
            val files = dir(context).listFiles() ?: emptyArray()
            for (file in files) {
                if (file.isFile && file.delete()) deleted++
            }
            val store = prefs(context)
            val editor = store.edit()
            for (key in store.all.keys) {
                // 配额两键是用户配置，清空副本时保留
                if (!key.startsWith(KEY_QUOTA_BYTES) && !key.startsWith(KEY_QUOTA_FILES)) editor.remove(key)
            }
            editor.apply()
        }
        return deleted
    }

    // ---------------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------------

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun fingerprintOf(size: Long, modifiedAt: Long): String = "$size|$modifiedAt"

    private fun digest(key: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray(Charsets.UTF_8))
        return hex(bytes).take(16)
    }

    private fun hex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    private const val HEX = "0123456789abcdef"

    /** 由来源路径推出 provider 能识别的安装名：保留原名主体，统一补 `.apk` */
    private fun installName(originPath: String): String {
        val raw = originPath.substringAfterLast('/')
        val lower = raw.lowercase()
        val cut = lower.lastIndexOf(".apk")
        val base = if (cut > 0) raw.substring(0, cut) else raw.substringBeforeLast('.', raw)
        val safe = buildString(base.length) {
            for (char in base) {
                if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') append(char)
            }
        }.trimStart('.').take(NAME_LIMIT)
        return (safe.ifEmpty { "apk" }) + ApkBridge.APK_SUFFIX
    }
}
