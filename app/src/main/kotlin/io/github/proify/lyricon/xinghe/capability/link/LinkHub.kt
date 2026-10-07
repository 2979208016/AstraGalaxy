package io.github.proify.lyricon.xinghe.capability.link

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import com.astraisland.protocol.ActivityBundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 智能上岛的唯一投递出口：链接和手机号共用一张卡。 */
object LinkHub {
    private const val TAG = "XingHe-SmartIsland"
    private const val CARD_ID = "xinghe-smart-island"
    private const val ACTION_OPEN = "open"
    private const val ACTION_DISMISS = "dismiss"
    private const val ACTION_PHONE_CONTACTS = "phone_contacts"
    private const val ACTION_PHONE_DIAL = "phone_dial"
    private val RETRY_DELAYS_MS = longArrayOf(300L, 500L, 800L, 1200L, 1800L, 2500L, 3000L)
    private const val CARD_PREFS = "smart_island_runtime"
    private const val CARD_TYPE = "type"
    private const val CARD_VALUE = "value"
    private const val STALE_MS = 120_000L
    private const val AWAIT_TIMEOUT_MS = 5_000L

    private sealed interface Card {
        data class Link(val url: String) : Card
        data class Phone(val number: String) : Card
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "xinghe-smart-island").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var client: IslandV7Client? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var pending: Card? = null
    @Volatile private var pendingAt = 0L
    @Volatile private var retryIndex = 0
    @Volatile private var current: Card? = null

    fun postBlocking(app: Context, url: String, timeoutMs: Long = AWAIT_TIMEOUT_MS): Boolean = postBlocking(app, Card.Link(url), timeoutMs)
    fun postPhoneBlocking(app: Context, number: String, timeoutMs: Long = AWAIT_TIMEOUT_MS): Boolean = postBlocking(app, Card.Phone(number), timeoutMs)

    private fun postBlocking(app: Context, card: Card, timeoutMs: Long): Boolean {
        appContext = app.applicationContext
        pending = card; pendingAt = System.currentTimeMillis(); retryIndex = 0
        val latch = CountDownLatch(1); val result = AtomicInteger(ActivityBundle.RESULT_NOT_CONNECTED)
        worker.execute { result.set(tryShow(card)); latch.countDown() }
        return runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS); result.get() == ActivityBundle.RESULT_OK || result.get() == ActivityBundle.RESULT_NOT_CONNECTED }.getOrDefault(false)
    }

    fun warmUp(app: Context) { appContext = app.applicationContext; worker.execute { ensureClient() } }
    fun isReady(): Boolean = client?.isReady == true
    fun stateName(): String = client?.state?.name ?: "未连接"
    fun protocolVersion(): Int = client?.islandProtocolVersion ?: 0

    fun dismiss() { worker.execute { runCatching { client?.end(CARD_ID) }; current = null; pending = null; appContext?.getSharedPreferences(CARD_PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply() } }

    private fun ensureClient(): IslandV7Client? {
        client?.let { return it }
        val ctx = appContext ?: return null
        restoreCurrent(ctx)
        return synchronized(this) {
            client ?: runCatching {
                IslandV7Client(ctx) { activityId, actionId -> onAction(activityId, actionId) }.apply {
                    onReadyChanged = { ready -> if (ready) main.post { flushPending() } }
                    connect()
                }
            }.onFailure { Log.e(TAG, "IslandV7Client create failed", it) }.getOrNull().also { client = it }
        }
    }

    private fun tryShow(card: Card): Int {
        val island = ensureClient() ?: return scheduleRetry(card)
        if (!island.isReady) { runCatching { island.connect() }; return scheduleRetry(card) }
        val result = runCatching { island.start(encodeCard(card)) }.onFailure { Log.e(TAG, "start failed", it) }.getOrDefault(ActivityBundle.RESULT_NOT_CONNECTED)
        Log.i(TAG, "start result=$result card=$card")
        when (result) {
            ActivityBundle.RESULT_OK -> { current = card; persistCurrent(card); pending = null; retryIndex = 0 }
            ActivityBundle.RESULT_NOT_CONNECTED -> { runCatching { island.connect() }; scheduleRetry(card) }
            else -> { pending = null; retryIndex = 0 }
        }
        return result
    }

    private fun scheduleRetry(card: Card): Int {
        if (retryIndex >= RETRY_DELAYS_MS.size) { retryIndex = 0; return ActivityBundle.RESULT_NOT_CONNECTED }
        val delay = RETRY_DELAYS_MS[retryIndex++]
        worker.execute { Thread.sleep(delay); if (pending == card) tryShow(card) }
        return ActivityBundle.RESULT_NOT_CONNECTED
    }

    private fun flushPending() {
        val card = pending ?: return
        if (System.currentTimeMillis() - pendingAt > STALE_MS) { pending = null; return }
        worker.execute { tryShow(card) }
    }

    private fun onAction(activityId: String, actionId: String) {
        if (activityId != CARD_ID) return
        val action = actionId.trim(); val ctx = appContext ?: return
        Log.i(TAG, "island action=$action current=$current")
        when (val card = current) {
            is Card.Link -> when (action) {
                ACTION_OPEN, "open_link" -> openLink(ctx, card.url)
                ACTION_DISMISS, "ignore", "dismiss_card" -> dismiss()
                else -> Log.w(TAG, "unknown link action=$action")
            }
            is Card.Phone -> when (action) {
                ACTION_PHONE_CONTACTS, "contacts", "add_contact", "save_contact" -> saveContact(ctx, card.number)
                ACTION_PHONE_DIAL, "dial", "call" -> dial(ctx, card.number)
                ACTION_DISMISS, "ignore", "dismiss_card" -> dismiss()
                else -> Log.w(TAG, "unknown phone action=$action")
            }
            null -> Log.w(TAG, "action without current card")
        }
    }

    private fun saveContact(ctx: Context, number: String) {
        launch(ctx, Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).setType(ContactsContract.Contacts.CONTENT_TYPE).putExtra(ContactsContract.Intents.Insert.PHONE, number))
    }

    private fun dial(ctx: Context, number: String) { launch(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))) }

    private fun launch(ctx: Context, intent: Intent) {
        main.post {
            val ok = runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
            Log.i(TAG, "launch action=${intent.action} ok=$ok")
            dismiss()
        }
    }

    private fun openLink(ctx: Context, url: String) { launch(ctx, Intent(ctx, LinkOpenActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))) }

    private fun persistCurrent(card: Card) {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(CARD_PREFS, Context.MODE_PRIVATE)
        when (card) {
            is Card.Link -> prefs.edit().putString(CARD_TYPE, "link").putString(CARD_VALUE, card.url).apply()
            is Card.Phone -> prefs.edit().putString(CARD_TYPE, "phone").putString(CARD_VALUE, card.number).apply()
        }
    }

    private fun restoreCurrent(ctx: Context) {
        if (current != null) return
        val prefs = ctx.getSharedPreferences(CARD_PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString(CARD_VALUE, null) ?: return
        current = when (prefs.getString(CARD_TYPE, null)) {
            "link" -> Card.Link(value)
            "phone" -> Card.Phone(value)
            else -> null
        }
    }

    private fun pendingIntent(intent: Intent, requestCode: Int): PendingIntent? {
        val ctx = appContext ?: return null
        return runCatching { PendingIntent.getActivity(ctx, requestCode, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }.getOrNull()
    }

    private fun openIntentFor(card: Card): PendingIntent? = when (card) {
        is Card.Link -> pendingIntent(Intent(Intent.ACTION_VIEW, Uri.parse(card.url)), card.url.hashCode())
        is Card.Phone -> pendingIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(card.number)}")), card.number.hashCode())
    }

    private fun phoneIcon(): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 9f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val path = Path().apply { moveTo(29f, 20f); cubicTo(22f, 25f, 22f, 38f, 31f, 53f); cubicTo(40f, 68f, 53f, 77f, 68f, 75f); lineTo(78f, 65f); lineTo(64f, 53f); lineTo(54f, 62f); cubicTo(46f, 58f, 38f, 50f, 34f, 42f); lineTo(43f, 32f); close() }
        Canvas(bitmap).drawPath(path, paint)
        return bitmap
    }

    private fun phoneIconSlot(): Bundle = ActivityBundle.encodeSlot(type = "icon", icon = ActivityBundle.encodeIcon(type = "bitmap", bitmap = phoneIcon()))

    private fun encodeCard(card: Card): Bundle {
        val leading = when (card) {
            is Card.Link -> ActivityBundle.encodeSlot(type = "icon", icon = ActivityBundle.encodeIcon(type = "builtin", builtin = "NAVIGATION"))
            is Card.Phone -> phoneIconSlot()
        }
        return when (card) {
            is Card.Link -> ActivityBundle.encodeActivity(id = CARD_ID, kind = "MESSAGE", compactLeading = leading, compactTrailing = ActivityBundle.encodeSlot("text", text = "链接"), expanded = ActivityBundle.encodeExpanded(template = "MESSAGE", title = "发现一个链接", subtitle = LinkPatterns.hostOf(card.url), body = card.url, actions = arrayListOf(ActivityBundle.encodeAction(ACTION_OPEN, "打开", primary = true), ActivityBundle.encodeAction(ACTION_DISMISS, "忽略"))), openIntent = openIntentFor(card), hideWhenSourceForeground = false, alertOnStart = true, alertOnUpdate = false)
            is Card.Phone -> ActivityBundle.encodeActivity(id = CARD_ID, kind = "MESSAGE", compactLeading = leading, compactTrailing = ActivityBundle.encodeSlot("text", text = "手机号"), expanded = ActivityBundle.encodeExpanded(template = "MESSAGE", title = "发现手机号", subtitle = card.number, body = "复制内容中检测到手机号", actions = arrayListOf(ActivityBundle.encodeAction(ACTION_PHONE_CONTACTS, "保存到通讯录", primary = true), ActivityBundle.encodeAction(ACTION_PHONE_DIAL, "拨号"), ActivityBundle.encodeAction(ACTION_DISMISS, "忽略", destructive = true))), openIntent = openIntentFor(card), hideWhenSourceForeground = false, alertOnStart = true, alertOnUpdate = false)
        }
    }
}
