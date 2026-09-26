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

/**
 * 链接助手详情页：开关、打开方式、状态提示。
 */
class LinkDetailActivity : BaseActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UIKit(this)
        prefs = ModulePrefs.of(this)
        setTheme(R.style.Theme_LyricProvider_Detail)
        // 进页面就连岛，状态才是真的；连不上也把原因显示出来
        runCatching { LinkHub.warmUp(this) }
        setContentView(wrapPage(scrollable(buildContent())))
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ui.dp(40))
        }

        root.addView(Toolbar.build(this, ui, getString(R.string.link_detail_title)) { finish() })
        root.addView(ui.gap(10))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), 0, ui.dp(18), 0)
        }
        root.addView(body)

        val switchCard = buildSwitchCard()
        body.addView(switchCard)
        Motion.fadeInUp(switchCard, 0L)

        body.addView(ui.gap(14))
        val browserCard = buildBrowserCard()
        body.addView(browserCard)
        Motion.fadeInUp(browserCard, 60L)

        body.addView(ui.gap(12))
        val howto = ui.text(getString(R.string.link_scope_hint), 12f, R.color.text_hint).apply {
            setLineSpacing(ui.dp(5).toFloat(), 1.12f)
        }
        body.addView(howto)
        Motion.fadeInUp(howto, 120L)

        body.addView(ui.gap(12))
        val privacy = ui.text(getString(R.string.link_privacy_body), 12f, R.color.text_hint).apply {
            setLineSpacing(ui.dp(5).toFloat(), 1.12f)
        }
        body.addView(privacy)
        Motion.fadeInUp(privacy, 150L)

        return root
    }

    private fun buildSwitchCard(): LinearLayout {
        val card = ui.glassCard()
        val on = ModulePrefs.isEnabled(prefs) && ModulePrefs.isLinkEnabled(prefs)
        card.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            ui.statusDot(if (on) R.color.ok else R.color.off),
            LinearLayout.LayoutParams(ui.dp(8), ui.dp(8))
        )
        row.addView(
            ui.text(
                getString(if (on) R.string.ui_link_on else R.string.ui_link_off),
                14f, if (on) R.color.ok else R.color.text_hint
            ).apply { setPadding(ui.dp(10), 0, 0, 0) },
            ui.weight()
        )
        row.addView(
            GlassSwitch(this).apply {
                setChecked(on, animate = false)
                onCheckedChange = { value ->
                    prefs.edit().putBoolean(ModulePrefs.KEY_LINK, value).apply()
                    toast(getString(if (value) R.string.ui_link_on else R.string.ui_link_off))
                    recreate()
                }
            }
        )
        card.addView(row)
        card.addView(ui.gap(12))
        card.addView(
            ui.text(
                "${getString(R.string.link_island_state)}：${islandStateLabel()}",
                12f,
                if (LinkHub.isReady()) R.color.ok else R.color.text_hint
            )
        )
        if (!ModuleEnabledState.isModuleEnabled(this)) {
            card.addView(ui.gap(6))
            card.addView(
                ui.text(getString(R.string.link_module_off), 12f, R.color.off).apply {
                    setLineSpacing(ui.dp(4).toFloat(), 1.1f)
                }
            )
        }
        return card
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
        val pinned = ModulePrefs.browserPackage(prefs)
        card.addView(
            ui.row(null, getString(R.string.link_browser), currentBrowserLabel(mode, pinned)) {
                showBrowserPicker()
            }
        )
        return card
    }

    private fun currentBrowserLabel(mode: String, pinned: String): String = when (mode) {
        ModulePrefs.BROWSER_PINNED ->
            BrowserChoice.installed(this)
                .firstOrNull { it.packageName == pinned }?.label
                ?: getString(R.string.link_browser_system)
        ModulePrefs.BROWSER_ASK -> getString(R.string.link_browser_ask)
        else -> getString(R.string.link_browser_system)
    }

    private fun showBrowserPicker() {
        val options = ArrayList<Pair<String, String>>()
        options.add(ModulePrefs.BROWSER_SYSTEM to getString(R.string.link_browser_system))
        options.add(ModulePrefs.BROWSER_ASK to getString(R.string.link_browser_ask))
        for (app in BrowserChoice.installed(this)) {
            options.add(app.packageName to app.label)
        }
        PickerSheet.show(this, getString(R.string.link_browser_pick), options) { key ->
            val editor = prefs.edit()
            when (key) {
                ModulePrefs.BROWSER_SYSTEM ->
                    editor.putString(ModulePrefs.KEY_BROWSER_MODE, ModulePrefs.BROWSER_SYSTEM)
                ModulePrefs.BROWSER_ASK ->
                    editor.putString(ModulePrefs.KEY_BROWSER_MODE, ModulePrefs.BROWSER_ASK)
                else -> {
                    editor.putString(ModulePrefs.KEY_BROWSER_MODE, ModulePrefs.BROWSER_PINNED)
                    editor.putString(ModulePrefs.KEY_BROWSER_PACKAGE, key)
                }
            }
            editor.apply()
            toast(getString(R.string.ui_browser_saved))
            recreate()
        }
    }
}
