package io.github.proify.lyricon.xinghe.ui.page

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import io.github.proify.lyricon.xinghe.R
import io.github.proify.lyricon.xinghe.capability.link.BrowserChoice
import io.github.proify.lyricon.xinghe.capability.link.LinkHub
import io.github.proify.lyricon.xinghe.settings.ModuleEnabledState
import io.github.proify.lyricon.xinghe.settings.ModulePrefs
import io.github.proify.lyricon.xinghe.ui.widget.GlassSwitch
import io.github.proify.lyricon.xinghe.ui.widget.Motion
import io.github.proify.lyricon.xinghe.ui.widget.UIKit

/** 智能上岛：总开关，以及链接、手机号两个独立开关。 */
class LinkDetailActivity : BaseActivity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UIKit(this)
        prefs = ModulePrefs.of(this)
        runCatching { LinkHub.warmUp(this) }
        setContentView(wrapPage(scrollable(buildContent())))
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, ui.dp(40)) }
        root.addView(Toolbar.build(this, ui, getString(R.string.link_detail_title)) { finish() })
        root.addView(ui.gap(10))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(18), 0, ui.dp(18), 0) }
        root.addView(body)
        val card = buildSwitchCard(); body.addView(card); Motion.fadeInUp(card, 0L)
        body.addView(ui.gap(14))
        val browser = buildBrowserCard(); body.addView(browser); Motion.fadeInUp(browser, 60L)
        body.addView(ui.gap(12))
        body.addView(ui.text(getString(R.string.link_scope_hint), 12f, R.color.text_hint).apply { setLineSpacing(ui.dp(5).toFloat(), 1.12f) })
        body.addView(ui.gap(12))
        body.addView(ui.text(getString(R.string.link_privacy_body), 12f, R.color.text_hint).apply { setLineSpacing(ui.dp(5).toFloat(), 1.12f) })
        return root
    }

    private fun buildSwitchCard(): LinearLayout {
        val card = ui.glassCard().apply { setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)) }
        val master = ModulePrefs.isEnabled(prefs) && ModulePrefs.isSmartIslandEnabled(prefs)
        card.addView(switchRow("智能上岛总开关", "关闭后不读取链接、手机号和文件事件", master, ModulePrefs.KEY_SMART_ISLAND))
        card.addView(ui.divider())
        card.addView(switchRow("链接助手", "复制链接后上岛", ModulePrefs.isLinkEnabled(prefs), ModulePrefs.KEY_LINK))
        card.addView(ui.divider())
        card.addView(switchRow("手机号上岛", "复制内容包含手机号时提供拨号或保存通讯录", ModulePrefs.isPhoneEnabled(prefs), ModulePrefs.KEY_PHONE))
        card.addView(ui.gap(12))
        card.addView(ui.text("${getString(R.string.link_island_state)}：${islandStateLabel()}", 12f, if (LinkHub.isReady()) R.color.ok else R.color.text_hint))
        if (!ModuleEnabledState.isModuleEnabled(this)) card.addView(ui.text(getString(R.string.link_module_off), 12f, R.color.off))
        return card
    }

    private fun switchRow(title: String, subtitle: String, checked: Boolean, key: String): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(5), 0, ui.dp(5)) }
        row.addView(ui.text(title, 14f, R.color.text, bold = true), ui.weight())
        val sw = GlassSwitch(this).apply {
            setChecked(checked, animate = false)
            onCheckedChange = { value -> prefs.edit().putBoolean(key, value).apply(); toast(if (value) "已开启：$title" else "已关闭：$title") }
        }
        row.addView(sw)
        row.setOnClickListener { sw.setChecked(!sw.isCheckedState()) }
        return row
    }

    private fun islandStateLabel(): String = when (LinkHub.stateName()) {
        "NOT_INSTALLED" -> getString(R.string.island_not_installed)
        "WAITING" -> getString(R.string.island_waiting)
        "REJECTED" -> getString(R.string.island_rejected)
        "READY" -> getString(R.string.island_ready)
        else -> LinkHub.stateName()
    }

    private fun buildBrowserCard(): LinearLayout {
        val card = ui.glassCard()
        val mode = ModulePrefs.browserMode(prefs)
        card.addView(ui.row(null, getString(R.string.link_browser), currentBrowserLabel(mode, ModulePrefs.browserPackage(prefs))) { showBrowserPicker() })
        return card
    }

    private fun currentBrowserLabel(mode: String, pinned: String): String = when (mode) {
        ModulePrefs.BROWSER_PINNED -> BrowserChoice.installed(this).firstOrNull { it.packageName == pinned }?.label ?: getString(R.string.link_browser_system)
        ModulePrefs.BROWSER_ASK -> getString(R.string.link_browser_ask)
        else -> getString(R.string.link_browser_system)
    }

    private fun showBrowserPicker() {
        val options = ArrayList<Pair<String, String>>()
        options.add(ModulePrefs.BROWSER_SYSTEM to getString(R.string.link_browser_system))
        options.add(ModulePrefs.BROWSER_ASK to getString(R.string.link_browser_ask))
        BrowserChoice.installed(this).forEach { options.add(it.packageName to it.label) }
        PickerSheet.show(this, getString(R.string.link_browser_pick), options) { key ->
            val editor = prefs.edit()
            if (key == ModulePrefs.BROWSER_SYSTEM || key == ModulePrefs.BROWSER_ASK) editor.putString(ModulePrefs.KEY_BROWSER_MODE, key)
            else editor.putString(ModulePrefs.KEY_BROWSER_MODE, ModulePrefs.BROWSER_PINNED).putString(ModulePrefs.KEY_BROWSER_PACKAGE, key)
            editor.apply(); toast(getString(R.string.ui_browser_saved)); recreate()
        }
    }
}
