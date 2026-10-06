package io.github.proify.lyricon.xinghe.capability.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel

/**
 * 星河岛客户端 · 协议 v7（接入库规范的广播会话通道）。
 *
 * 为什么不能继续用 aar 里的 IslandClient：
 * 新版星流在 REGISTER 广播里要求 protocolVersion >= 7，低于 7 直接丢弃
 *（岛侧日志「register: unsupported protocol v5」），而且 IIslandSession 因为
 * 去掉了 update()，事务号整体前移：bind=1, start=2, end=3, endAll=4,
 * listMine=5, getProtocolVersion=6。aar 固定发 5、按老事务号通信，
 * 所以注册永远被丢，表现就是「星河岛 未连接」。
 *
 * 这里按接入库规范在源码里实现同一套握手：
 *
 *   1. 向 systemui 广播 REGISTER { client: Binder, source: 包名, protocolVersion: 7 }；
 *   2. 岛回调 client binder 的 onSessionReady(sessionBinder, version)（客户端事务 1）；
 *   3. 会话代理 transact：bind=1 → start=2(Bundle) → end=3(id,outro) → endAll=4；
 *   4. 收到 ISLAND_READY 广播（岛重启 / 上线）时重新注册；
 *   5. 岛侧再用 source 包名校验 PUBLISH_ACTIVITY 权限（manifest 已声明）。
 *
 * 卡片 Bundle 的键（id/kind/compact*、expanded/…）新旧版一致，沿用
 * com.astraisland.protocol.ActivityBundle 的编码器即可，不需要变。
 *
 * 对外形态刻意与旧 IslandClient 一致（state/isReady/connect/start/end），
 * LinkHub 只需要换一个类型名。
 */
internal class IslandV7Client(
    private val context: Context,
    private val onAction: (activityId: String, actionId: String) -> Unit
) {

    enum class State { NOT_INSTALLED, WAITING, READY, REJECTED }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile var state: State = State.WAITING
        private set

    @Volatile var islandProtocolVersion: Int = 0
        private set

    var onReadyChanged: ((Boolean) -> Unit)? = null

    /** 卡片生命周期事件（onExpanded/onDismissedByUser 等），链接助手用不到，留出口子 */
    var onEvent: ((activityId: String, name: String, timestampMs: Long) -> Unit)? = null

    val isReady: Boolean get() = session != null

    @Volatile private var session: IBinder? = null
    @Volatile private var desired = false
    @Volatile private var listening = false
    private var generation = 0L
    private var deathRecipient: IBinder.DeathRecipient? = null

    /** 注册超时：这么久没等到 onSessionReady，就告诉上层「还没连上」 */
    private val timeoutRunnable = Runnable {
        if (desired && state == State.WAITING && session == null) {
            onReadyChanged?.invoke(false)
        }
    }

    private val readyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (desired && listening && intent.action == ACTION_ISLAND_READY) register()
        }
    }

    // ---------------- 连接管理 ----------------

    fun connect() {
        if (Looper.myLooper() != handler.looper) {
            handler.post { connect() }
            return
        }
        desired = true
        if (!isIslandInstalled()) {
            state = State.NOT_INSTALLED
            onReadyChanged?.invoke(false)
            return
        }
        if (!listening) {
            listening = runCatching {
                val filter = IntentFilter(ACTION_ISLAND_READY)
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(readyReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    context.registerReceiver(readyReceiver, filter)
                }
                true
            }.getOrDefault(false)
            if (!listening) {
                desired = false
                state = State.REJECTED
                onReadyChanged?.invoke(false)
                return
            }
        }
        register()
    }

    fun disconnect() {
        if (Looper.myLooper() != handler.looper) {
            handler.post { disconnect() }
            return
        }
        desired = false
        generation++
        handler.removeCallbacks(timeoutRunnable)
        session?.let { runCatching { sessionEndAll(it) } }
        clearSession()
        state = State.WAITING
        if (listening) {
            runCatching { context.unregisterReceiver(readyReceiver) }
            listening = false
        }
    }

    /** 发 REGISTER 广播，等岛回呼 onSessionReady */
    private fun register() {
        if (!desired) return
        val wasReady = session != null
        clearSession()
        generation++
        state = State.WAITING
        if (wasReady) onReadyChanged?.invoke(false)
        val extras = Bundle().apply {
            putBinder(EXTRA_CLIENT, ClientBinder(generation))
            putString(EXTRA_SOURCE, context.packageName)
            putInt(EXTRA_PROTOCOL_VERSION, PROTOCOL_VERSION)
        }
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_REGISTER).setPackage(SYSTEM_UI_PACKAGE).putExtras(extras)
            )
        }
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, CONNECT_TIMEOUT_MS)
    }

    private fun clearSession() {
        val old = session
        session = null
        deathRecipient?.let { d -> old?.let { runCatching { it.unlinkToDeath(d, 0) } } }
        deathRecipient = null
    }

    /** 会话到手：先 bind()，再挂死亡监听，然后 READY */
    private fun bindSession(binder: IBinder, version: Int, attempt: Long) {
        if (version < PROTOCOL_VERSION) {
            state = State.REJECTED
            handler.removeCallbacks(timeoutRunnable)
            onReadyChanged?.invoke(false)
            return
        }
        val ok = runCatching { sessionBind(binder) }.getOrDefault(false)
        if (!ok) {
            clearSession()
            generation++
            state = State.REJECTED
            handler.removeCallbacks(timeoutRunnable)
            onReadyChanged?.invoke(false)
            return
        }
        val death = IBinder.DeathRecipient {
            handler.post {
                if (desired && attempt == generation && session === binder) {
                    clearSession()
                    state = State.WAITING
                    onReadyChanged?.invoke(false)
                }
            }
        }
        val linked = runCatching { binder.linkToDeath(death, 0); true }.getOrDefault(false)
        if (!linked) {
            clearSession()
            state = State.WAITING
            onReadyChanged?.invoke(false)
            return
        }
        clearSession()
        deathRecipient = death
        session = binder
        islandProtocolVersion = version
        state = State.READY
        handler.removeCallbacks(timeoutRunnable)
        onReadyChanged?.invoke(true)
    }

    // ---------------- 会话调用 ----------------

    /** 投一张卡；返回值是岛侧结果码（0=OK，9=未连接，其余见 ActivityBundle.RESULT_*） */
    fun start(card: Bundle): Int {
        val s = session ?: return RESULT_NOT_CONNECTED
        return runCatching { sessionStart(s, card) }.getOrDefault(RESULT_NOT_CONNECTED)
    }

    fun end(id: String): Int {
        val s = session ?: return RESULT_NOT_CONNECTED
        return runCatching { sessionEnd(s, id, null) }.getOrDefault(RESULT_NOT_CONNECTED)
    }

    fun endAll() {
        val s = session ?: return
        runCatching { sessionEndAll(s) }
    }

    fun listMine(): List<String> {
        val s = session ?: return emptyList()
        return runCatching { sessionListMine(s) }.getOrDefault(emptyList())
    }

    // ---------------- 客户端回调 Binder（岛 → 我们） ----------------

    private inner class ClientBinder(private val attempt: Long) : Binder() {

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(CLIENT_DESCRIPTOR)
                return true
            }
            return try {
                when (code) {
                    T_ON_SESSION_READY -> {
                        data.enforceInterface(CLIENT_DESCRIPTOR)
                        val binder = data.readStrongBinder()
                        val version = data.readInt()
                        onSessionReady(binder, version)
                        true
                    }
                    T_ON_SESSION_LOST -> {
                        data.enforceInterface(CLIENT_DESCRIPTOR)
                        onSessionLost()
                        true
                    }
                    T_ON_ACTION -> {
                        data.enforceInterface(CLIENT_DESCRIPTOR)
                        val activityId = data.readString()
                        val actionId = data.readString()
                        dispatchAction(activityId, actionId)
                        true
                    }
                    in T_EVENT_FIRST..T_EVENT_LAST -> {
                        data.enforceInterface(CLIENT_DESCRIPTOR)
                        val activityId = data.readString()
                        val timestamp = data.readLong()
                        dispatchEvent(EVENT_NAMES[code] ?: "event$code", activityId, timestamp)
                        true
                    }
                    T_ON_REPLY -> {
                        // 消息卡片的「回复」回调：链接卡片没有回复框，读掉参数即可
                        data.enforceInterface(CLIENT_DESCRIPTOR)
                        data.readString(); data.readString()
                        true
                    }
                    T_ON_SEEK -> {
                        // 媒体卡片的「拖动进度」回调：链接卡片没有进度条
                        data.enforceInterface(CLIENT_DESCRIPTOR)
                        data.readString(); data.readLong()
                        true
                    }
                    else -> super.onTransact(code, data, reply, flags)
                }
            } catch (t: Throwable) {
                super.onTransact(code, data, reply, flags)
            }
        }

        private fun onSessionReady(binder: IBinder?, version: Int) {
            if (binder == null) return
            handler.post {
                if (desired && attempt == generation) bindSession(binder, version, attempt)
            }
        }

        private fun onSessionLost() {
            handler.post {
                if (!desired || attempt != generation) return@post
                val had = session != null
                clearSession()
                generation++
                state = if (state == State.WAITING) State.REJECTED else State.WAITING
                if (had || state == State.REJECTED) onReadyChanged?.invoke(false)
            }
        }

        private fun dispatchAction(activityId: String?, actionId: String?) {
            if (activityId == null || actionId == null) return
            handler.post {
                if (desired && attempt == generation && session != null) {
                    onAction(activityId, actionId)
                }
            }
        }

        private fun dispatchEvent(name: String, activityId: String?, timestamp: Long) {
            if (activityId == null) return
            handler.post {
                if (desired && attempt == generation && session != null) {
                    onEvent?.invoke(activityId, name, timestamp)
                }
            }
        }
    }

    // ---------------- 会话代理（我们 → 岛），新版事务号 ----------------

    private fun sessionBind(binder: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SESSION_DESCRIPTOR)
            binder.transact(T_BIND, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun sessionStart(binder: IBinder, card: Bundle): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SESSION_DESCRIPTOR)
            writeTyped(data, card)
            binder.transact(T_START, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** 新版 end(id, outro)：outro 是可选的收尾 Bundle，链接卡片不用，传 null */
    private fun sessionEnd(binder: IBinder, id: String, outro: Bundle?): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SESSION_DESCRIPTOR)
            data.writeString(id)
            writeTyped(data, outro)
            binder.transact(T_END, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun sessionEndAll(binder: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SESSION_DESCRIPTOR)
            binder.transact(T_END_ALL, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun sessionListMine(binder: IBinder): List<String> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SESSION_DESCRIPTOR)
            binder.transact(T_LIST_MINE, data, reply, 0)
            reply.readException()
            reply.createStringArrayList().orEmpty()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** AIDL typed-object 写法（低 API 没有 Parcel.writeTypedObject，按生成代码的样子手写） */
    private fun writeTyped(parcel: Parcel, value: Bundle?) {
        if (value == null) {
            parcel.writeInt(0)
        } else {
            parcel.writeInt(1)
            value.writeToParcel(parcel, 0)
        }
    }

    // ---------------- 宿主探测 ----------------

    /**
     * 星流是否装着。规范按 meta-data HOST_PROTOCOL 判定；个别版本可能不写
     * meta-data，所以包在但读不到标记时也放行 —— 协议是否被接受由岛侧说了算，
     * 这里误判成「未安装」反而永远连不上。
     */
    private fun isIslandInstalled(): Boolean {
        for (pkg in HOST_PACKAGES) {
            val meta = runCatching {
                val pm = context.packageManager
                val info = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, PackageManager.GET_META_DATA)
                }
                info.applicationInfo?.metaData?.getInt(HOST_METADATA, -1) ?: -1
            }.getOrDefault(-2)
            when {
                meta >= 5 -> return true      // meta-data 标记明确支持
                meta == -2 -> continue        // 包不存在 / 读不到
                else -> return true           // 包装着但没标记：交给岛侧拒绝
            }
        }
        return false
    }

    private companion object {
        // 广播动作与 extra（接入库规范，新旧一致）
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val ACTION_REGISTER = "com.astraisland.action.REGISTER"
        private const val ACTION_ISLAND_READY = "com.astraisland.action.ISLAND_READY"
        private const val EXTRA_CLIENT = "client"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_PROTOCOL_VERSION = "protocolVersion"

        /** 新版岛的最低协议号；注册时带它，老 aar 发的 5 会被岛丢掉 */
        private const val PROTOCOL_VERSION = 7

        private const val CLIENT_DESCRIPTOR = "com.astraisland.protocol.IIslandClient"
        private const val SESSION_DESCRIPTOR = "com.astraisland.protocol.IIslandSession"

        // IIslandClient 回调事务号（新版：1-12，含新增的 onReply/onSeek）
        private const val T_ON_SESSION_READY = 1
        private const val T_ON_SESSION_LOST = 2
        private const val T_ON_ACTION = 3
        private const val T_EVENT_FIRST = 4     // onExpanded
        private const val T_EVENT_LAST = 10     // onEndedBySystem
        private const val T_ON_REPLY = 11
        private const val T_ON_SEEK = 12

        // IIslandSession 调用事务号（新版：update() 移除后整体前移）
        private const val T_BIND = 1
        private const val T_START = 2
        private const val T_END = 3
        private const val T_END_ALL = 4
        private const val T_LIST_MINE = 5

        // 岛侧 start() 结果码（与 ActivityBundle.RESULT_* 一致）
        private const val RESULT_NOT_CONNECTED = 9

        private val EVENT_NAMES = mapOf(
            4 to "onExpanded",
            5 to "onCollapsed",
            6 to "onDismissedByUser",
            7 to "onPromoted",
            8 to "onDemoted",
            9 to "onExpired",
            10 to "onEndedBySystem"
        )

        private const val HOST_METADATA = "com.astraisland.HOST_PROTOCOL"
        private val HOST_PACKAGES = listOf("com.astraflow.tool", "com.astraflow.tool.debug")
        private const val CONNECT_TIMEOUT_MS = 3_000L
    }
}
