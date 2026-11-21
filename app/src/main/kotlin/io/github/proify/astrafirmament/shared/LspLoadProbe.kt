package io.github.proify.astrafirmament.shared

/**
 * LSPosed 模块加载探针：从框架日志里找 hook 自己打的加载行。
 *
 * 两种证据（按可信度）：
 *  1. `link-hook <版本> loaded`——[io.github.proify.astrafirmament.hook.LinkHook.install]
 *     的第一行，只在 system_server 里真的执行到 install() 时才出现，且带版本；
 *  2. `AstraGalaxy loaded in system_server`——HookEntry.onModuleLoaded 打的入口行，
 *     证明模块代码已注入 system_server，但 hook 还在挂载窗口内（版本未知）。
 *
 * 为什么不用「IslandService started」：那行只有出过岛卡才写，没出过卡就误报未加载。
 *
 * ★ 用服务端 grep 而不是 tail 固定窗口：加载行在开机后十几秒就写进日志开头，
 * 之后日志会涨到几百 KB 以上（实测 212KB），任何固定 tail 窗口都会把它切掉，
 * 反倒误报「未加载」。读 /data/adb 必须走 su（应用 uid 读不到），
 * [probe] 会阻塞，调用方负责放后台线程。
 */
internal object LspLoadProbe {

    /** 框架日志目录（LSPosed 每次开机新建一个 modules_<时间戳>.log） */
    private const val LOG_DIR = "/data/adb/lspd/log"
    private const val MAX_FILES = 3
    private const val MAX_LINES = 5
    private const val LIST_TIMEOUT_MS = 4_000L
    private const val SCAN_TIMEOUT_MS = 6_000L

    /** 两个证据行的联合匹配（grep -E），一次扫一个文件 */
    private const val GREP_PATTERN = "link-hook [^ ]* loaded|AstraGalaxy loaded in system_server"

    private val LOADED_LINE = Regex("""link-hook (\S+) loaded""")
    private val ENTRY_LINE = Regex("""AstraGalaxy loaded in system_server""")

    /** 路径白名单：拼进 shell 命令前必须校验，杜绝注入 */
    private val SAFE_PATH = Regex("""/data/adb/lspd/log/modules_[0-9A-Za-z._:+-]+\.log\z""")

    /**
     * @param loaded 是否找到加载证据
     * @param version 日志记录的模块版本；为 null 表示只看到入口行（hook 未挂载完）
     * @param fromLog 命中的日志文件名（排障用）
     */
    data class Result(
        val loaded: Boolean,
        val version: String? = null,
        val fromLog: String? = null,
    )

    /** 探测一次。无 root / 无日志 / 无命中 → loaded=false */
    fun probe(): Result {
        var entryOnly: Result? = null
        for (path in newestLogs()) {
            val lines = evidenceLines(path)
            val version = lines.mapNotNull { LOADED_LINE.find(it)?.groupValues?.get(1) }.lastOrNull()
            if (version != null) return Result(true, version, path.substringAfterLast('/'))
            if (entryOnly == null && lines.any { ENTRY_LINE.containsMatchIn(it) }) {
                entryOnly = Result(true, null, path.substringAfterLast('/'))
            }
        }
        return entryOnly ?: Result(false)
    }

    /** 一个日志文件里的证据行（取最后几行，覆盖同一会话内的多次挂载） */
    private fun evidenceLines(path: String): List<String> {
        val out = SuReader.read(
            "grep -E '$GREP_PATTERN' '$path' 2>/dev/null | tail -n $MAX_LINES",
            SCAN_TIMEOUT_MS,
        ) ?: return emptyList()
        return out.lineSequence().filter { it.isNotBlank() }.toList()
    }

    /** 最新的几个框架日志（ls -t 按 mtime 倒序，最新在前） */
    private fun newestLogs(): List<String> {
        val listing = SuReader.read(
            "ls -t $LOG_DIR/modules_*.log 2>/dev/null | head -n $MAX_FILES",
            LIST_TIMEOUT_MS,
        ) ?: return emptyList()
        return listing.lineSequence()
            .map { it.trim() }
            .filter(SAFE_PATH::matches)
            .take(MAX_FILES)
            .toList()
    }
}
