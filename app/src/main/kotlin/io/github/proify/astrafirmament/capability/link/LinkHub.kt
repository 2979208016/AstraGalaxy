package io.github.proify.astrafirmament.capability.link

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import com.astraisland.sdk.BuiltinSymbol
import com.astraisland.sdk.ButtonStyle
import com.astraisland.sdk.Capsule
import com.astraisland.sdk.CapsuleTrailing
import com.astraisland.sdk.DismissPolicy
import com.astraisland.sdk.GenericCard
import com.astraisland.sdk.IslandActivity
import com.astraisland.sdk.IslandCallback
import com.astraisland.sdk.IslandClient
import com.astraisland.sdk.IslandImage
import com.astraisland.sdk.IslandResult
import com.astraisland.sdk.Priority
import com.astraisland.sdk.TextButton
import io.github.proify.astrafirmament.settings.ModulePrefs
import java.util.concurrent.Executors
import io.github.proify.astrafirmament.hook.LinkHook
import io.github.proify.astrafirmament.R
import io.github.proify.astrafirmament.app.ApkBridge
import io.github.proify.astrafirmament.app.SmartApkIntake

/**
 * 智能上岛：卡片构造 + 模块进程侧的出卡/按钮执行。
 *
 * 架构——「system 检测 + 透明页拉活 + 模块出卡 + system 代发跳转」：
 *
 * ```
 * 复制 → system_server 剪贴板 hook（LinkHook，永生）
 *      → IslandService.connectAndShow：system 显式 bindService 拉活模块进程
 *        （AMS 对 system 身份放行；绑定期间模块进程不可冻结/查杀）
 *      → 模块进程 LinkHub.showCardFromModule：IslandClient 连星流出卡
 *        （模块 uid 与包名一致 → 星流 bind 校验通过）
 *      → 点按钮 → 广播 ACTION_LAUNCH 发回 system_server → 代发 startActivity
 *        （system 豁免 BAL；模块进程后台 start 会被拦）
 * ```
 *
 * 每个环节都在它「天然被允许」的进程里执行：检测在系统（hook 注入）、
 * 出卡在模块（uid 归属正确）、跳转在系统（BAL 豁免）。模块进程被拉活
 * 完成出卡后即可回收——零常驻、清后台不影响（下次复制 system 再拉）。
 */
object LinkHub {
    private const val TAG = "AstraFirmament-SmartIsland"
    private const val CARD_ID = "xinghe-smart-island"
    private const val ACTION_OPEN = "open"
    private const val ACTION_DISMISS = "dismiss"
    private const val ACTION_PHONE_CONTACTS = "phone_contacts"
    private const val ACTION_PHONE_DIAL = "phone_dial"
    private const val ACTION_FILE_INSTALL = "file_install"
    private const val ACTION_FILE_VIEW = "file_view"
    private const val ACTION_FILE_SHARE = "file_share"
    private const val ACTION_FILE_DELETE = "file_delete"
    private const val ACTION_WIFI_PASS = "wifi_pass"
    private const val ACTION_WIFI_SHARE = "wifi_share"
    private const val ACTION_WIFI_SETTINGS = "wifi_settings"
    private const val ACTION_BT_DISCONNECT = "bt_disconnect"
    private const val ACTION_BT_OFF = "bt_off"
    private const val ACTION_BT_SETTINGS = "bt_settings"

    /** 断开态胶囊图标的颜色（Material Red 400）：和连接态的白色图标区分 */
    private const val DISCONNECTED_COLOR = 0xFFEF5350.toInt()

    /** root 只读的 WiFi 配置来源：新 ROM 是 XML 配置库，旧 ROM 是 supplicant */
    private const val WIFI_CONFIG_STORE = "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml"
    private val SUPPLICANT_PATHS = listOf(
        "/data/misc/wifi/wpa_supplicant.conf",
        "/data/misc/apexdata/com.android.wifi/wpa_supplicant.conf"
    )
    private val MOST_RECENT_RE = Regex("""<boolean name="IsMostRecentlyConnected" value="true"""")
    private val SSID_XML_RE = Regex("""<string name="SSID">([^<]*)</string>""")
    private val CONFIG_KEY_XML_RE = Regex("""<string name="ConfigKey">([^<]*)</string>""")
    private val PSK_XML_RE = Regex("""<string name="(?:PreSharedKey|SAE-Password)">([^<]*)</string>""")
    private val WEP_XML_RE = Regex("""<string name="WEP-?Keys">([^<]*)</string>""")
    private val XML_QUOTED_RE = Regex("\"([^\"]*)\"")
    private val SSID_CONF_RE = Regex("""ssid="([^"]*)"""")
    private val PSK_CONF_RE = Regex("""psk="([^"]*)"""")

    /** 跨进程广播：模块 → system_server 的按钮代发（与 LinkHook 常量对齐） */
    private const val ACTION_LAUNCH = "io.github.proify.astrafirmament.LAUNCH"
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_TARGET = "xinghe_target"
    private const val EXTRA_PKG = "xinghe_pkg"
    private const val EXTRA_ASK = "xinghe_ask"

    /** 当前卡片内容（按钮回调要用） */
    @Volatile private var currentUrl: String? = null
    @Volatile private var currentNumber: String? = null
    @Volatile private var currentWifi: String? = null
    @Volatile private var currentFile: String? = null
    @Volatile private var currentBluetooth: String? = null

    @Volatile private var client: IslandClient? = null
    @Volatile private var appContext: Context? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 岛时长收起任务（单卡共用——新卡弹出时先取消旧任务再重排） */
    private val lingerTask = Runnable {
        endCard()
        // 卡已经自己到点消失了：清零「仍有卡在显示」的状态，前台服务随之收工
        cardShownAt = 0L
        cardLifeMs = 0L
        DevLog.i("island auto-ended after linger")
    }

    /** 链路线程：出卡重试与按钮回调处理 */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xinghe-island").apply { isDaemon = true }
    }

    private val RETRY_DELAYS_MS = longArrayOf(300L, 500L, 800L, 1200L, 1800L, 2500L, 3000L)

    /** 同一次检测的投递凭证有效期（跨通道去重） */
    private const val TOKEN_DEDUP_MS = 10_000L

    /** 出卡成功后同内容的静默窗：与宿主「每 10s 只允许自动展开一次」对齐 */
    private const val SHOWN_DEDUP_MS = 10_000L

    /** 广播与 startService 几乎同时到达，1.5s 内合并为一张卡 */
    private const val ATTEMPT_DEDUP_MS = 1_500L

    // ---------------- 出卡（模块进程） ----------------

    /** system 侧连接已就绪（IslandService 复用连接用） */
    /** UI 侧状态查询（Compose 壳的首页状态条在用，勿改名） */
    fun isReady(): Boolean = client?.isReady == true

    /**
     * 出卡去重分三层，避免 7.11 的「多通道各出一张卡、后到的把前一张的
     * 保活窗口算成 0」：
     *  - token：同一次检测生成的投递凭证，10s 内只处理一次（跨三条通道）；
     *  - 已成功：同 kind+稳定身份 10s 内不再出卡（宿主也限制 10s 一次自动展开）；
     *  - 在途：同身份 1.5s 内合并（广播与 startService 几乎同时到达）。
     * 注意：不得在**尝试前**记 10s 成功窗，否则一次失败要等 10s 才能重试。
     */
    @Volatile private var lastShownIdentity: String? = null
    @Volatile private var lastShownAt: Long = 0L
    @Volatile private var lastAttemptIdentity: String? = null
    @Volatile private var lastAttemptAt: Long = 0L
    private val seenTokens = HashMap<String, Long>()

    /** 当前卡片实际显示时长（毫秒）与弹出时刻：前台服务保护窗口要与它严格对齐 */
    @Volatile private var cardLifeMs: Long = 0L
    @Volatile private var cardShownAt: Long = 0L

    /**
     * 模块进程出卡（IslandService 调）。IslandClient 异步连星流，带退避重试
     * 直到出卡或耗尽；期间进程受「system 绑定中」保护，不会被冻结打断。
     */
    fun showCardFromModule(
        ctx: Context,
        kind: String,
        target: String,
        token: String = "",
        onDone: ((shown: Boolean) -> Unit)? = null
    ) {
        appContext = ctx.applicationContext
        DevLog.refresh(ctx)
        // 开关闸门（跨进程投递缓存窗口兜底）：总开关+对应子开关任一关即拦
        if (!isKindAllowed(ctx, kind)) {
            DevLog.w("showCard blocked by switch: kind=$kind")
            runCatching { onDone?.invoke(false) }
            return
        }
        // 去重（调用线程先挡一道，避免 worker 队列堆重复任务）
        if (dedupDrop(kind, target, token)) {
            DevLog.i("showCard dedup drop kind=$kind token=${token.take(8)}")
            runCatching { onDone?.invoke(false) }
            return
        }
        DevLog.i("showCard kind=$kind token=${token.take(8)}")
        worker.execute {
            var shown = false
            try {
                shown = startWithRetry(ctx, kind, target)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
            } finally {
                // shown 必须回传：调用方（IslandService）靠它决定保活窗口，
                // 把「被去重/被开关挡下」当成出卡成功就会立刻收掉前台服务，
                // 卡片随后弹出但按钮回调已经没人接。
                val result = shown
                runCatching { onDone?.invoke(result) }
            }
        }
    }

    /**
     * 当前卡片还要显示多久（毫秒）；0 = 没有在显示的卡。
     *
     * IslandService 用它决定前台服务该保活多久：窗口必须 ≤ 卡片生命，
     * 否则会出现「卡还在、保护窗口已过」（进程被冻结/回收后按钮点不动）。
     */
    fun remainingCardMs(): Long {
        val shownAt = cardShownAt
        if (shownAt <= 0L || cardLifeMs <= 0L) return 0L
        return (shownAt + cardLifeMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /**
     * 这次出卡会不会被开关挡下（[showCardFromModule] 内部用的是同一个判断）。
     *
     * 给「本进程直接出卡」的链路用：那条路没有广播回执，调用方必须先知道这一步做没做，
     * 否则会把一次被静默丢弃的出卡记成「已出卡」，用户打开开关后就再也等不到这张卡。
     */
    fun canShow(ctx: Context, kind: String): Boolean = isKindAllowed(ctx, kind)

    /** 开关闸门：总开关 + 对应子开关 */
    private fun isKindAllowed(ctx: Context, kind: String): Boolean {
        val prefs = ModulePrefs.ofFresh(ctx)
        return ModulePrefs.isEnabled(prefs) && ModulePrefs.isSmartIslandEnabled(prefs) && when (kind) {
            "phone" -> ModulePrefs.isPhoneEnabled(prefs)
            "link" -> ModulePrefs.isLinkEnabled(prefs)
            "file" -> ModulePrefs.isFileInstallerEnabled(prefs)
            "wifi" -> ModulePrefs.isWifiEnabled(prefs)
            "bt" -> ModulePrefs.isBluetoothEnabled(prefs)
            else -> false
        }
    }

    /**
     * 出卡准入（见字段注释的三层去重）。
     * 成功窗放在 [onCardShown] 里记；这里只记「在途」，失败后可立即重试。
     */
    private fun dedupDrop(kind: String, target: String, token: String): Boolean {
        val now = System.currentTimeMillis()
        val identity = identityOf(kind, target)
        // token 去重：跨通道同一凭证只放行第一条，10s 后自动淘汰
        synchronized(seenTokens) {
            seenTokens.entries.removeAll { now - it.value > TOKEN_DEDUP_MS }
            if (token.isNotBlank() && seenTokens.put(token, now) != null) return true
        }
        synchronized(this) {
            if (identity == lastShownIdentity && now - lastShownAt < SHOWN_DEDUP_MS) return true
            if (identity == lastAttemptIdentity && now - lastAttemptAt < ATTEMPT_DEDUP_MS) return true
            lastAttemptIdentity = identity
            lastAttemptAt = now
            return false
        }
    }

    /**
     * 去重用的稳定身份：卡片正文里会变的展示内容不参与比较。
     *
     * WiFi 卡的 target 里带「实测网速」，同一次连接状态每次测出来都不一样，
     * 用它做去重键等于永远命不中：CONNECTIVITY_ACTION 抖几下就重出一张卡、
     * 每次都重排收起任务并再起一次前台服务（旧定时器提前 stopSelf → 卡还活着
     * 但没有进程处理按钮）。所以这里只保留「连接状态 + SSID」。
     */
    private fun identityOf(kind: String, target: String): String = when (kind) {
        "wifi" -> if (target.startsWith(LinkHook.WIFI_DISCONNECTED)) LinkHook.WIFI_DISCONNECTED
        else LinkHook.WIFI_CONNECTED_PREFIX + wifiSsidOf(target)
        else -> target
    }

    /** 卡片显示时长（秒）：唯一真值在 ModulePrefs，已夹紧到 [10,150] */
    private fun cardLifeSeconds(ctx: Context, kind: String): Int = runCatching {
        ModulePrefs.lingerSecondsFor(ModulePrefs.ofFresh(ctx), featureOf(kind))
    }.getOrDefault(ModulePrefs.DEFAULT_CARD_LINGER_SECONDS)

    /**
     * 出卡主体（worker 线程）：kind 直传（wifi/file 不落进 link 兜底），带退避重试。
     *
     * 返回值 = 本进程是否真的把卡片交给了宿主。调用方（IslandService）用它算保活窗口：
     * 只有真出卡才需要为「卡片还活着」继续留前台服务。
     */
    private fun startWithRetry(ctx: Context, kind: String, target: String): Boolean {
        val spec = CardSpec(kind, target)
        var attempt = 0
        var result: IslandResult = IslandResult.NOT_CONNECTED
        while (true) {
            val island = runCatching { ensureClient(ctx) }.getOrElse {
                // 建不出连接（宿主未装/被禁用）：不再空转，交给外层收尾并重建
                DevLog.e("ensureClient failed kind=$kind", it)
                result = IslandResult.NOT_CONNECTED
                break
            }
            val activity = buildActivity(spec)
            result = runCatching { island.start(activity) }
                .onFailure { Log.e(TAG, "start failed", it) }
                .getOrDefault(IslandResult.NOT_CONNECTED)
            DevLog.i("start result=$result kind=$kind attempt=$attempt")
            if (result == IslandResult.QUOTA_EXCEEDED) {
                // 岛侧配额满（MAX_CONCURRENT_PER_SOURCE=3）：顶替最旧腾配额重试一次
                evictOldestForQuota()
                result = runCatching { island.start(activity) }
                    .onFailure { Log.e(TAG, "start retry failed", it) }
                    .getOrDefault(IslandResult.NOT_CONNECTED)
                DevLog.i("start retry result=$result kind=$kind")
            }
            if (result != IslandResult.NOT_CONNECTED) break
            if (attempt >= RETRY_DELAYS_MS.size) break
            runCatching { island.connect() }
            Thread.sleep(RETRY_DELAYS_MS[attempt])
            attempt++
        }
        if (result == IslandResult.OK) {
            onCardShown(ctx, spec)
        } else if (result == IslandResult.NOT_CONNECTED) {
            // 退避重试用完还没连上：连接大概率已经坏了（宿主重启/binder 断），
            // 直接丢掉，下次出卡时新建一个，别拿着死连接反复空转。
            dropClient("retry exhausted")
        }
        DevLog.i("showCard done result=$result")
        return result == IslandResult.OK
    }

    /** 出卡成功：单卡字段赋值 + 持久层（进程回收回退用）+ 到点自动收起 */
    private fun onCardShown(ctx: Context, spec: CardSpec) {
        runCatching {
            ModulePrefs.ofFresh(ctx).edit()
                .putString(ModulePrefs.KEY_CUR_KIND, spec.kind)
                .putString(ModulePrefs.KEY_CUR_TARGET, spec.target)
                .apply()
        }
        bindCurrentTarget(spec)
        // 显示时长：该功能单独设过就用它的，否则用默认值；已夹紧到 [10,150] 秒，
        // 与 buildActivity 的 DismissPolicy 是同一个函数，两边不会漂移。
        val lifeSec = cardLifeSeconds(ctx, spec.kind)
        cardLifeMs = lifeSec * 1000L
        cardShownAt = System.currentTimeMillis()
        // 成功窗：宿主限制自动展开 10s 一次，模块侧按同一节奏去重
        lastShownIdentity = identityOf(spec.kind, spec.target)
        lastShownAt = cardShownAt
        mainHandler.removeCallbacks(lingerTask)
        mainHandler.postDelayed(lingerTask, cardLifeMs)
    }

    /** 单卡模型：切换卡片时先把四个旧目标清空，避免按钮拿到上一张卡的内容 */
    private fun bindCurrentTarget(spec: CardSpec) {
        currentNumber = null; currentUrl = null; currentWifi = null; currentFile = null
        currentBluetooth = null
        when (spec.kind) {
            "phone" -> currentNumber = spec.target
            "wifi" -> currentWifi = spec.target
            "file" -> currentFile = spec.target
            "bt" -> currentBluetooth = spec.target
            else -> currentUrl = spec.target
        }
    }

    /** 卡类型 → 偏好里的功能名（各功能可单独设置收起时长） */
    private fun featureOf(kind: String): String = when (kind) {
        "phone" -> "phone"
        "wifi" -> "wifi"
        "file" -> "file"
        "bt" -> "bt"
        else -> "link"
    }

    /** 配额满时腾位：单卡模型 end 自己的卡片即可释放配额 */
    private fun evictOldestForQuota() {
        runCatching { client?.end(CARD_ID) }
        DevLog.w("quota full, evicted own card to free slot")
    }

    /** 首页状态胶囊：连一下星流看状态（模块自己的进程，只读展示） */
    fun warmUp(app: Context) {
        DevLog.refresh(app)
        DevLog.i("warmUp from ui")
        appContext = app.applicationContext
        // 连接与清旧卡都可能走 binder，不能占 UI 线程
        worker.execute {
            runCatching { ensureClient(app.applicationContext) }
            cleanStaleCard()
        }
    }

    /**
     * 进程重启后岛上可能还挂着上一进程的卡：没有 cardShownAt 就说明本进程不知道
     * 它为什么在。此时把持久层目标对应的卡 end 掉并清目标，避免「卡在、点了没反应」。
     */
    private fun cleanStaleCard() {
        if (cardShownAt > 0L) return
        val ctx = appContext ?: return
        val stale = runCatching {
            val p = ModulePrefs.ofFresh(ctx)
            !p.getString(ModulePrefs.KEY_CUR_KIND, null).isNullOrBlank()
        }.getOrDefault(false)
        if (!stale) return
        DevLog.w("stale card found on startup, ending it")
        dismiss()
    }

    /** 服务入口先把 App 上下文交给 LinkHub（FGS 失败时要能收旧卡/清持久层） */
    fun attachContext(ctx: Context) {
        appContext = ctx.applicationContext
    }

    /** FGS 建立失败时由 IslandService 调：本次卡片直接作废，不留死卡 */
    fun abortShow(reason: String) {
        DevLog.w("island abort show: $reason")
        worker.execute { dismiss() }
    }

    fun stateName(): String = client?.state?.name ?: "未连接"

    private fun ensureClient(ctx: Context): IslandClient {
        client?.let { return it }
        synchronized(this) {
            client ?: runCatching {
                IslandClient(ctx).apply {
                    setCallback(callback)
                    connect()
                }
            }.onFailure { Log.e(TAG, "IslandClient create failed", it) }.getOrNull()?.also { client = it }
            // 创建失败时抛出前先兜底：重试一次不现实（调用方带退避循环），
            // 直接抛让外层 runCatching 吃掉，由退避循环重进
            return client ?: throw IllegalStateException("IslandClient unavailable")
        }
    }

    /**
     * 丢弃坏连接：下次出卡时重新建一个 IslandClient。
     *
     * 连接一旦坏掉（宿主进程重启、binder 断开），旧 client 会永远停在非 READY
     * 状态；以前存在就复用、永不重建，等于出卡链路从此半死。
     */
    private fun dropClient(reason: String) {
        val old = client
        client = null
        DevLog.w("island client dropped: $reason")
        runCatching { old?.disconnect() }
    }

    /**
     * 丢卡（用户点「知道了/忽略」或到点自动收起）的唯一出口。
     *
     * 不能只写 `client?.end(CARD_ID)`：应用进程被系统回收后重开时内存里的 client
     * 是空的，而岛上那张卡还在显示——这时按钮点了就「没反应」。这里用一次性连接
     * 把它关掉，用完立即断开；没有可用的 App 上下文时返回 false。
     */
    private fun endCard(): Boolean {
        val live = client
        if (live != null) return runCatching { live.end(CARD_ID) }.isSuccess
        val ctx = appContext ?: return false
        val temp = runCatching {
            IslandClient(ctx).apply {
                setCallback(callback)
                connect()
            }
        }.getOrNull() ?: return false
        val ok = runCatching { temp.end(CARD_ID) }.isSuccess
        DevLog.i("island end via temp client ok=$ok")
        runCatching { temp.disconnect() }
        return ok
    }

    private val callback = object : IslandCallback() {
        override fun onStateChanged(state: IslandClient.State) {
            DevLog.i("island state -> $state")
            // 宿主没装/被禁用：清掉连接，等下次出卡时重建（别拿着坏连接一直用）
            if (state == IslandClient.State.NOT_INSTALLED) dropClient("host not installed")
        }

        override fun onAction(activityId: String, actionId: String) {
            if (activityId != CARD_ID) return
            worker.execute { onAction(actionId) }
        }
    }

    /** 按钮点击：模块进程组装参数，广播给 system_server 代发 startActivity */
    private fun onAction(actionId: String) {
        val action = actionId.trim()
        DevLog.i("island action=$action")
        val ctx = appContext ?: return
        var number = currentNumber
        var url = currentUrl
        var wifiTarget = currentWifi
        var fileTarget = currentFile
        var btTarget = currentBluetooth
        // 进程重启后内存字段为空：从持久层回退（岛还挂着，按钮必须能执行）
        restoreActionTarget(ctx)?.let { (k, t) ->
            when (k) {
                "phone" -> number = t
                "wifi" -> wifiTarget = t
                "file" -> fileTarget = t
                "bt" -> btTarget = t
                else -> url = t
            }
        }
        when {
            btTarget != null -> handleBtAction(ctx, action, btTarget)
            wifiTarget != null -> handleWifiAction(ctx, action, wifiTarget)
            fileTarget != null -> handleFileAction(ctx, action, fileTarget)
            number != null -> handlePhoneAction(ctx, action, number)
            url != null -> handleLinkAction(ctx, action, url)
            // 一个目标都没有：卡还在但模块这边不知道它是谁（进程被回收过、
            // 持久层也没留下）。留痕 + 收掉这张死卡，别让用户对着点不动的卡。
            else -> {
                DevLog.w("island action=$action dropped: no target, ending stale card")
                dismiss()
            }
        }
    }

    /** 持久层回退：四字段全空时取当前卡目标，返回 (kind, target) */
    private fun restoreActionTarget(ctx: Context): Pair<String, String>? {
        if (currentNumber != null || currentUrl != null || currentWifi != null ||
            currentFile != null || currentBluetooth != null
        ) return null
        return runCatching {
            val p = ModulePrefs.ofFresh(ctx)
            val k = p.getString(ModulePrefs.KEY_CUR_KIND, null)
            val t = p.getString(ModulePrefs.KEY_CUR_TARGET, null)
            if (!k.isNullOrBlank() && !t.isNullOrBlank()) {
                DevLog.i("action target restored from prefs: kind=$k")
                k to t
            } else null
        }.getOrNull()
    }

    private fun handleBtAction(ctx: Context, action: String, btTarget: String) {
        val name = bluetoothNameOf(btTarget)
        when (action) {
            ACTION_BT_DISCONNECT, "disconnect" -> {
                dismiss(); sendLaunch(ctx, LinkHook.KIND_BT_DISCONNECT, btTarget)
                DevLog.i("bluetooth disconnect requested: $name")
                toast(ctx, "正在断开「$name」")
            }
            ACTION_BT_OFF, "bt_off", "turn_off" -> {
                dismiss(); sendLaunch(ctx, LinkHook.KIND_BT_OFF, btTarget)
                DevLog.i("bluetooth disable requested")
                toast(ctx, "正在关闭蓝牙")
            }
            ACTION_BT_SETTINGS, "settings" -> {
                dismiss(); sendLaunch(ctx, LinkHook.KIND_BT_SETTINGS, btTarget)
            }
            ACTION_DISMISS, "ignore", "dismiss_card" -> dismiss()
        }
    }

    /** 卡片 target → 设备名（`bt:<名字>|<MAC>`） */
    private fun bluetoothNameOf(btTarget: String): String =
        btTarget.removePrefix(LinkHook.BT_TARGET_PREFIX)
            .substringBefore(LinkHook.BT_TARGET_SEP)
            .ifBlank { "蓝牙设备" }

    private fun handleWifiAction(ctx: Context, action: String, wifiTarget: String) {
        val ssid = wifiSsidOf(wifiTarget)
        when (action) {
            ACTION_WIFI_PASS, "password" -> { dismiss(); copyWifiPassword(ctx, ssid) }
            ACTION_WIFI_SHARE, "share_pass" -> { dismiss(); shareWifiPassword(ctx, ssid) }
            ACTION_WIFI_SETTINGS, "settings" -> {
                dismiss(); sendLaunch(ctx, LinkHook.KIND_WIFI_SETTINGS, wifiTarget)
            }
            ACTION_DISMISS, "ignore", "dismiss_card" -> dismiss()
        }
    }

    /** 卡片 target → SSID（占位符原样交给解析层兜底，别在这里伪造名字） */
    private fun wifiSsidOf(wifiTarget: String): String =
        wifiTarget.removePrefix(LinkHook.WIFI_CONNECTED_PREFIX).substringBefore(LinkHook.WIFI_TARGET_SEP)

    /**
     * 复制密码：写剪贴板交给 system 代做——模块进程在后台，自己 setPrimaryClip
     * 会被 appops 静默拦掉（点了像没反应），按钮必须在「天然被允许」的进程里执行。
     */
    private fun copyWifiPassword(ctx: Context, ssid: String) {
        val cred = resolveWifiCredential(ssid)
        if (cred == null) {
            DevLog.w("wifi password read failed: ssid=$ssid")
            toast(ctx, wifiFailureText(ssid, "复制"))
            return
        }
        val pass = cred.password
        if (pass.isNullOrBlank()) {
            DevLog.w("wifi has no password: ${cred.ssid}")
            toast(ctx, "「${cred.ssid}」是开放网络，无需密码")
            return
        }
        sendLaunch(ctx, LinkHook.KIND_CLIP, pass)
        DevLog.i("wifi password copied: ssid=${cred.ssid}")
        toast(ctx, "已复制「${cred.ssid}」的密码")
    }

    /** 分享密码：分享面板必须由 system 弹出（模块后台 startActivity 会被 BAL 拦） */
    private fun shareWifiPassword(ctx: Context, ssid: String) {
        val cred = resolveWifiCredential(ssid)
        if (cred == null) {
            DevLog.w("wifi password read failed: ssid=$ssid")
            toast(ctx, wifiFailureText(ssid, "分享"))
            return
        }
        val pass = cred.password
        if (pass.isNullOrBlank()) {
            DevLog.w("wifi has no password: ${cred.ssid}")
            toast(ctx, "「${cred.ssid}」是开放网络，无需密码")
            return
        }
        // 分享就是分享：不再顺手写剪贴板（用户明确区分「分享密码」与「复制密码」）。
        sendLaunch(ctx, LinkHook.KIND_TEXT_SHARE, "WiFi：${cred.ssid}\n密码：$pass")
        DevLog.i("wifi password shared: ssid=${cred.ssid}")
        toast(ctx, "已准备「${cred.ssid}」的分享内容")
    }

    /** 失败原因分流：「没 root 权限」和「没这条记录」是两回事，用户要能自救 */
    private fun wifiFailureText(ssid: String, doing: String): String =
        if (io.github.proify.astrafirmament.shared.SuReader.available())
            "${doing}失败：未找到「$ssid」的密码记录"
        else "${doing}失败：需要 root 权限才能读取 WiFi 密码"

    /** 用户可见反馈：按钮回调在 worker 线程，Toast 必须回主线程 */
    private fun toast(ctx: Context, message: String) {
        mainHandler.post {
            runCatching {
                android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleFileAction(ctx: Context, action: String, fileTarget: String) {
        when (action) {
            ACTION_FILE_INSTALL, "install" -> {
                dismiss(); sendLaunch(ctx, io.github.proify.astrafirmament.hook.LinkHook.KIND_FILE, fileTarget)
            }
            ACTION_FILE_SHARE, "share" -> {
                dismiss(); sendLaunch(ctx, io.github.proify.astrafirmament.hook.LinkHook.KIND_SHARE, fileTarget)
            }
            ACTION_FILE_VIEW, "view" -> {
                dismiss(); sendLaunch(ctx, io.github.proify.astrafirmament.hook.LinkHook.KIND_FILE_VIEW, fileTarget)
            }
            ACTION_FILE_DELETE, "delete" -> {
                dismiss(); deleteFile(ctx, fileTarget)
            }
            ACTION_DISMISS, "ignore", "dismiss_card" -> dismiss()
        }
    }

    /**
     * 删除要删「用户的原始安装包」：副本由我方 Provider 提供时，只有 App 进程删得掉
     * （system_server uid 1000 读写不了 /storage）；其余来源仍交给 system 代发，保持旧行为。
     */
    private fun deleteFile(ctx: Context, fileTarget: String) {
        val app = ctx.applicationContext
        val uri = runCatching {
            android.net.Uri.parse(fileTarget.substringAfter('|', ""))
        }.getOrNull()
        if (uri == null || !ApkBridge.isOurUri(app, uri)) {
            sendLaunch(ctx, io.github.proify.astrafirmament.hook.LinkHook.KIND_DELETE, fileTarget)
            return
        }
        worker.execute {
            val message = when (SmartApkIntake.deleteOriginal(app, fileTarget)) {
                SmartApkIntake.DeleteResult.DELETED -> R.string.smart_file_delete_done
                SmartApkIntake.DeleteResult.UNKNOWN -> R.string.smart_file_delete_unknown
                SmartApkIntake.DeleteResult.FAILED -> R.string.smart_file_delete_failed
            }
            toast(app, app.getString(message))
        }
    }

    private fun handlePhoneAction(ctx: Context, action: String, number: String) {
        when (action) {
            ACTION_PHONE_CONTACTS, "contacts", "add_contact", "save_contact" -> {
                dismiss(); sendLaunch(ctx, io.github.proify.astrafirmament.hook.LinkHook.KIND_CONTACTS, number)
            }
            ACTION_PHONE_DIAL, "dial", "call" -> {
                dismiss(); sendLaunch(ctx, io.github.proify.astrafirmament.hook.LinkHook.KIND_DIAL, number)
            }
            ACTION_DISMISS, "ignore", "dismiss_card" -> dismiss()
        }
    }

    private fun handleLinkAction(ctx: Context, action: String, url: String) {
        when (action) {
            ACTION_OPEN, "open_link" -> {
                dismiss()
                // 浏览器策略：模块进程读自己的偏好（这里才是正确的家）
                val prefs = ModulePrefs.ofFresh(ctx)
                val mode = ModulePrefs.browserMode(prefs)
                val pinned = ModulePrefs.browserPackage(prefs)
                val pkg = if (mode == ModulePrefs.BROWSER_PINNED && pinned.isNotBlank() &&
                    runCatching { ctx.packageManager.getPackageInfo(pinned, 0) }.isSuccess
                ) pinned else ""
                sendLaunch(ctx, io.github.proify.astrafirmament.hook.LinkHook.KIND_LINK, url, pkg, mode == ModulePrefs.BROWSER_ASK)
            }
            ACTION_DISMISS, "ignore", "dismiss_card" -> dismiss()
        }
    }

    /** 读到的 WiFi 凭据；password 为空表示该配置没有密码（开放/企业网络） */
    private data class WifiCredential(
        val ssid: String,
        val password: String?,
        val mostRecent: Boolean = false
    )

    /**
     * 解析 SSID 对应密码。卡片里的 SSID 可能取错（无定位权限时是占位符），
     * 所以按「卡上 SSID → root 读到的真实当前 SSID → 最近一次连接的配置」
     * 逐级兜底，而不是一次匹配失败就报错。
     */
    private fun resolveWifiCredential(ssidHint: String): WifiCredential? = runCatching {
        val hint = ssidHint.takeUnless { it == LinkHook.WIFI_SSID_UNKNOWN }.orEmpty()
        // ① 新 ROM：WifiConfigStore.xml（SSID/密码值带 &quot; 实体转义）
        suCat(WIFI_CONFIG_STORE)?.let { xml ->
            val entries = parseWifiConfigStore(xml)
            matchWifiEntry(entries, hint)?.let { return@runCatching it }
            // 卡上名字不可信时，用 root 读到的真实当前 SSID 再匹配一次
            LinkHook.currentWifiSsid()?.let { real ->
                matchWifiEntry(entries, real)?.let {
                    DevLog.i("wifi credential matched by real ssid: ${it.ssid}")
                    return@runCatching it
                }
            }
            // 最后兜底：配置库里最近一次连接的网络就是这张卡的网络
            entries.firstOrNull { it.mostRecent }?.let {
                DevLog.w("wifi credential fallback to most-recent: ${it.ssid} (hint=$ssidHint)")
                return@runCatching it
            }
        }
        // ② 旧 ROM：wpa_supplicant.conf
        readSupplicantCredential(hint)
    }.getOrNull()

    /** WifiConfigStore.xml → 每个网络一条（SSID / 密码 / 是否最近连接） */
    private fun parseWifiConfigStore(xml: String): List<WifiCredential> =
        xml.split("<WifiConfiguration>").mapNotNull { block ->
            val ssid = ssidOfConfigBlock(block) ?: return@mapNotNull null
            WifiCredential(ssid, passwordOfConfigBlock(block), MOST_RECENT_RE.containsMatchIn(block))
        }

    private fun ssidOfConfigBlock(block: String): String? {
        SSID_XML_RE.find(block)?.let { return nonBlank(xmlUnescape(it.groupValues[1])) }
        // 少数 ROM 没有独立 SSID 字段，ConfigKey 形如 "SSID"WPA_PSK
        CONFIG_KEY_XML_RE.find(block)?.let { m ->
            XML_QUOTED_RE.find(xmlUnescape(m.groupValues[1]))
                ?.let { return nonBlank(it.groupValues[1]) }
        }
        return null
    }

    /** 密码字段：PreSharedKey/SAE-Password 优先；WEP 存多把 key，取第一把非空 */
    private fun passwordOfConfigBlock(block: String): String? {
        PSK_XML_RE.find(block)?.let { m ->
            nonBlank(xmlUnescape(m.groupValues[1]))?.let { return it }
        }
        WEP_XML_RE.find(block)?.let { m ->
            val raw = xmlUnescape(m.groupValues[1])
            XML_QUOTED_RE.findAll(raw).map { it.groupValues[1] }
                .firstOrNull { it.isNotBlank() }?.let { return it }
            nonBlank(raw)?.let { return it }
        }
        return null
    }

    private fun matchWifiEntry(entries: List<WifiCredential>, ssid: String): WifiCredential? {
        val want = normalizeSsid(ssid)
        if (want.isBlank()) return null
        return entries.firstOrNull { normalizeSsid(it.ssid) == want }
    }

    /** 旧 ROM：wpa_supplicant.conf 里 ssid="..." 块内 psk="..." */
    private fun readSupplicantCredential(ssidHint: String): WifiCredential? {
        val want = normalizeSsid(ssidHint)
        if (want.isBlank()) return null
        for (path in SUPPLICANT_PATHS) {
            val text = suCat(path) ?: continue
            for (block in text.split("network={")) {
                val ssid = SSID_CONF_RE.find(block)?.groupValues?.get(1) ?: continue
                if (ssid != want) continue
                val psk = PSK_CONF_RE.find(block)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                return WifiCredential(ssid, psk)
            }
        }
        return null
    }

    /** SSID 两侧统一：去引号/空白后才能比较（XML 里的值可能还带实体） */
    private fun normalizeSsid(ssid: String): String = ssid.trim().trim('"').trim()

    private fun nonBlank(raw: String): String? = raw.trim().trim('"').trim().takeIf { it.isNotBlank() }

    /** XML 实体反转义（按序：amp 最后处理避免二次转义） */
    private fun xmlUnescape(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun suCat(path: String): String? =
        io.github.proify.astrafirmament.shared.SuReader.read("cat '$path'", timeoutMs = 6_000L)

    /**
     * 广播代发：发给 system_server 里 LinkHook 注册的动态 receiver。
     * system 豁免 BAL，点了就跳。
     *
     * 目标字段可能含敏感内容（Wi-Fi 密码、带凭证的副本 URI），因此发送时
     * 指定接收方签名级权限（[LinkHook.PERMISSION_ISLAND_COMMAND]）：只有持有该
     * 权限的本签名应用与 system_server（core uid）能收到，其他应用无法窃听。
     * 接收端仍以同一权限校验发送方，形成双向门禁。
     */
    private fun sendLaunch(ctx: Context, kind: String, target: String, pkg: String = "", ask: Boolean = false) {
        runCatching {
            // 实测（ColorOS 16）：setPackage("android") 定向广播不投递给
            // system_server 内动态注册的 receiver（按钮全无反应的根因）。
            // 因此不按包定向，改用接收方签名级权限限制收听范围。
            val intent = Intent(ACTION_LAUNCH)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_ASK, ask)
            ctx.sendBroadcast(intent, LinkHook.PERMISSION_ISLAND_COMMAND)
            DevLog.i("launch[$kind] broadcast sent")
        }
    }

    // ---------------- 卡片构造 ----------------

    data class CardSpec(val kind: String, val target: String)

    private fun buildActivity(spec: CardSpec): IslandActivity =
        IslandActivity.Builder(CARD_ID, capsuleFor(spec), cardFor(spec))
            .setPriority(Priority.HIGH)
            .setOpenIntent(null) // 已知限制：跨包 PendingIntent 签名不可用；按钮是主交互
            .setAlertOnStart(true)
            .setAlertOnUpdate(false)
            .setPostedAt(System.currentTimeMillis())
            // 卡片有效期与「模块进程保活」严格对齐：进程一停（shortService
            // 最多 150 秒），卡片也必须已经自行消失，否则会留下「卡还在、
            // 点按钮没人处理」的死卡（用户反馈的 WiFi 岛放久了点不动）。
            .setDismissPolicy(DismissPolicy.afterMillis(cardLifeMsFor(spec.kind)))
            .build()

    /**
     * 卡片存活时长（毫秒）——必须与 LinkHub.onCardShown 的收起任务、
     * IslandService 的保活窗口取同一个值（[cardLifeSeconds]）。
     *
     * 这里以前硬编码读「link」功能的时长，导致 WiFi/蓝牙/手机/文件卡
     * 各自设的时长形同虚设：卡按 link 的时长留着，进程却按自己功能的
     * 时长先停了，于是出现「卡还在、点按钮没反应」。
     */
    private fun cardLifeMsFor(kind: String): Long {
        val ctx = appContext ?: return (ModulePrefs.DEFAULT_CARD_LINGER_SECONDS * 1000L)
        return cardLifeSeconds(ctx, kind) * 1000L
    }

    private fun capsuleFor(spec: CardSpec): Capsule {
        val (icon, label) = when (spec.kind) {
            "phone" -> IslandImage.picture(phoneIcon()) to "手机号"
            // 断开态用红色带斜杠的图标，避免和连接态的白色 WiFi 图标混淆
            "wifi" -> IslandImage.picture(wifiIcon(connected = !spec.target.startsWith("disconnected"))) to "WiFi"
            "file" -> IslandImage.picture(fileIcon()) to "安装包"
            "bt" -> IslandImage.picture(bluetoothIcon()) to "蓝牙"
            else -> IslandImage.symbol(BuiltinSymbol.NAVIGATION) to "链接"
        }
        return Capsule.Builder(icon)
            .setTrailing(CapsuleTrailing.text(label))
            .build()
    }

    private fun cardFor(spec: CardSpec): GenericCard = when (spec.kind) {
        "phone" -> phoneCard(spec.target)
        "wifi" -> wifiCard(spec.target)
        "file" -> fileCard(spec.target)
        "bt" -> bluetoothCard(spec.target)
        else -> linkCard(spec.target)
    }

    private fun phoneCard(number: String): GenericCard =
        GenericCard.Builder("发现手机号")
            .setSubtitle(number)
            .setBody("复制内容中检测到手机号")
            .addButton(TextButton(ACTION_PHONE_CONTACTS, "保存到通讯录", ButtonStyle.PRIMARY))
            .addButton(TextButton(ACTION_PHONE_DIAL, "拨号"))
            .addButton(TextButton(ACTION_DISMISS, "忽略", ButtonStyle.DESTRUCTIVE))
            .build()

    /** 断开卡=已断开+查看网络/知道了；连接卡=WiFi名+已连接+网速+复制/分享密码/知道了 */
    private fun wifiCard(wifiTarget: String): GenericCard {
        if (wifiTarget.startsWith("disconnected")) {
            return GenericCard.Builder("WiFi 已断开")
                .setSubtitle("网络连接已断开")
                .setBody("去查看可用的网络")
                .addButton(TextButton(ACTION_WIFI_SETTINGS, "查看网络", ButtonStyle.PRIMARY))
                .addButton(TextButton(ACTION_DISMISS, "知道了", ButtonStyle.DESTRUCTIVE))
                .build()
        }
        val (ssid, speed) = wifiCardLines(wifiTarget)
        return GenericCard.Builder(ssid)
            .setSubtitle("已连接")
            .setBody("网速：${speed.ifBlank { "测量中" }}")
            .addButton(TextButton(ACTION_WIFI_PASS, "复制密码", ButtonStyle.PRIMARY))
            .addButton(TextButton(ACTION_WIFI_SHARE, "分享密码"))
            .addButton(TextButton(ACTION_DISMISS, "知道了", ButtonStyle.DESTRUCTIVE))
            .build()
    }

    /** target → (SSID, 网速)；占位符说明 SSID 没解析出来，标题退回中性文案 */
    private fun wifiCardLines(wifiTarget: String): Pair<String, String> {
        val raw = wifiTarget.removePrefix(LinkHook.WIFI_CONNECTED_PREFIX)
        val parts = raw.split(LinkHook.WIFI_TARGET_SEP, limit = 2)
        val ssid = parts.getOrNull(0)
            ?.takeIf { it.isNotBlank() && it != LinkHook.WIFI_SSID_UNKNOWN } ?: "WiFi"
        return ssid to parts.getOrNull(1).orEmpty()
    }

    private fun bluetoothCard(btTarget: String): GenericCard =
        GenericCard.Builder(bluetoothNameOf(btTarget))
            .setSubtitle("已连接")
            .setBody("蓝牙设备已连接，可直接断开或关闭蓝牙")
            .addButton(TextButton(ACTION_BT_DISCONNECT, "断开连接", ButtonStyle.PRIMARY))
            .addButton(TextButton(ACTION_BT_OFF, "关闭蓝牙"))
            .addButton(TextButton(ACTION_BT_SETTINGS, "设置"))
            .addButton(TextButton(ACTION_DISMISS, "知道了", ButtonStyle.DESTRUCTIVE))
            .build()

    /** target: apk:<文件名>|<uri>；4 按钮用满 SDK 上限 */
    /**
     * 下载完成的安装包卡：副标题是文件名，正文给「包名 + 下载时间 + 大小」——
     * 用户要靠这三样判断这是不是自己刚下载的那个包（同名文件在下载目录里太常见了）。
     *
     * 按钮顺序按用户要求：分享 / 查看 / 安装 / 知道了（「知道了」永远在最后）。
     */
    private fun fileCard(fileTarget: String): GenericCard {
        val name = fileTarget.removePrefix("apk:").substringBefore('|').ifBlank { "安装包" }
        val uri = runCatching { android.net.Uri.parse(fileTarget.substringAfter('|')) }.getOrNull()
        val pkg = uri?.getQueryParameter(ApkBridge.QUERY_PACKAGE).orEmpty()
        val size = uri?.getQueryParameter(ApkBridge.QUERY_SIZE)?.toLongOrNull() ?: 0L
        val modifiedAt = uri?.getQueryParameter(ApkBridge.QUERY_MODIFIED)?.toLongOrNull() ?: 0L
        return GenericCard.Builder("下载完成")
            .setSubtitle(name)
            .setBody(fileCardBody(pkg, size, modifiedAt))
            .addButton(TextButton(ACTION_FILE_SHARE, "分享"))
            .addButton(TextButton(ACTION_FILE_VIEW, "查看"))
            .addButton(TextButton(ACTION_FILE_INSTALL, "安装", ButtonStyle.PRIMARY))
            .addButton(TextButton(ACTION_DISMISS, "知道了", ButtonStyle.DESTRUCTIVE))
            .build()
    }

    /** 卡片正文：三行以内，缺哪项就写「未知」，不编造 */
    private fun fileCardBody(pkg: String, size: Long, modifiedAt: Long): String = buildString {
        append("包名：").append(pkg.ifBlank { "未知" })
        append('\n').append("下载于：").append(formatTime(modifiedAt))
        if (size > 0L) append('\n').append("大小：").append(formatSize(size))
    }

    private fun formatTime(millis: Long): String {
        if (millis <= 0L) return "未知"
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
        }.getOrDefault("未知")
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun linkCard(url: String): GenericCard =
        GenericCard.Builder("发现一个链接")
            .setSubtitle(LinkPatterns.hostOf(url))
            .setBody(url)
            .addButton(TextButton(ACTION_OPEN, "打开", ButtonStyle.PRIMARY))
            .addButton(TextButton(ACTION_DISMISS, "忽略"))
            .build()

    /** 连接态=白色 WiFi 弧形图标；断开态=红色图标 + 斜杠（两种各自缓存） */
    @Volatile private var cachedWifiIconOn: Bitmap? = null
    @Volatile private var cachedWifiIconOff: Bitmap? = null

    private fun wifiIcon(connected: Boolean): Bitmap {
        (if (connected) cachedWifiIconOn else cachedWifiIconOff)?.let { return it }
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (connected) Color.WHITE else DISCONNECTED_COLOR
            style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND
        }
        Canvas(bitmap).apply {
            drawArc(android.graphics.RectF(38f, 38f, 58f, 58f), 0f, 180f, false, paint)
            drawArc(android.graphics.RectF(26f, 26f, 70f, 70f), 0f, 180f, false, paint)
            drawArc(android.graphics.RectF(14f, 14f, 82f, 82f), 0f, 180f, false, paint)
            paint.style = Paint.Style.FILL
            drawCircle(48f, 52f, 5f, paint)
            if (!connected) {
                // 斜杠：胶囊缩到状态栏大小时也能一眼分出断开态
                paint.style = Paint.Style.STROKE
                drawLine(20f, 20f, 76f, 76f, paint)
            }
        }
        if (connected) cachedWifiIconOn = bitmap else cachedWifiIconOff = bitmap
        return bitmap
    }

    /** 蓝牙胶囊图标：自绘蓝牙符文（竖线 + 两个三角折角），不依赖宿主资源 */
    @Volatile private var cachedBluetoothIcon: Bitmap? = null

    private fun bluetoothIcon(): Bitmap {
        cachedBluetoothIcon?.let { return it }
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 7.5f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        Canvas(bitmap).apply {
            // 符文：中竖线 + 右上/右下两个折角（传统蓝牙符号）
            drawLine(48f, 18f, 48f, 78f, paint)
            drawLine(48f, 18f, 70f, 36f, paint)
            drawLine(70f, 36f, 32f, 66f, paint)
            drawLine(48f, 78f, 70f, 60f, paint)
            drawLine(70f, 60f, 32f, 30f, paint)
        }
        cachedBluetoothIcon = bitmap
        return bitmap
    }

    /** 安装包胶囊图标：自绘「盒子 + 向下箭头」，不依赖宿主资源 */
    @Volatile private var cachedFileIcon: Bitmap? = null

    private fun fileIcon(): Bitmap {
        cachedFileIcon?.let { return it }
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        Canvas(bitmap).apply {
            drawRect(android.graphics.RectF(22f, 24f, 74f, 76f), paint)
            drawLine(22f, 40f, 74f, 40f, paint)
            drawLine(48f, 48f, 48f, 66f, paint)
            drawLine(39f, 57f, 48f, 66f, paint)
            drawLine(57f, 57f, 48f, 66f, paint)
        }
        cachedFileIcon = bitmap
        return bitmap
    }

    private fun phoneIcon(): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 9f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val path = Path().apply { moveTo(29f, 20f); cubicTo(22f, 25f, 22f, 38f, 31f, 53f); cubicTo(40f, 68f, 53f, 77f, 68f, 75f); lineTo(78f, 65f); lineTo(64f, 53f); lineTo(54f, 62f); cubicTo(46f, 58f, 38f, 50f, 34f, 42f); lineTo(43f, 32f); close() }
        Canvas(bitmap).drawPath(path, paint)
        return bitmap
    }

    private fun dismiss() {
        endCard()
        currentNumber = null; currentUrl = null; currentWifi = null; currentFile = null
        currentBluetooth = null
        // 卡已经不在了：清掉「仍有卡在显示」的状态，前台服务随之收工
        cardShownAt = 0L
        cardLifeMs = 0L
        mainHandler.removeCallbacks(lingerTask)
        // 持久层一并清（防旧目标被下一次点击误用）
        runCatching {
            appContext?.let {
                ModulePrefs.ofFresh(it).edit()
                    .remove(ModulePrefs.KEY_CUR_KIND)
                    .remove(ModulePrefs.KEY_CUR_TARGET)
                    .apply()
            }
        }
    }
}
