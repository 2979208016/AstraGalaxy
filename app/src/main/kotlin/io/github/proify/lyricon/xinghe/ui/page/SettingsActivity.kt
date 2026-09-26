package io.github.proify.lyricon.xinghe.ui.page

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import io.github.proify.lyricon.xinghe.R
import io.github.proify.lyricon.xinghe.settings.ModulePrefs
import io.github.proify.lyricon.xinghe.ui.widget.GlassSwitch
import io.github.proify.lyricon.xinghe.ui.widget.Motion
import io.github.proify.lyricon.xinghe.ui.widget.UIKit

/**
 * 设置与关于。桌面入口指向本页（activity-alias）。
 * 功能开关都在首页的能力卡上，这里只放模块级开关、快捷入口和关于。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UIKit(this)
        prefs = ModulePrefs.of(this)
        syncLauncherIcon()
        setContentView(wrapPage(scrollable(buildContent())))
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), ui.dp(24), ui.dp(18), ui.dp(40))
        }
        root.addView(ui.text(getString(R.string.home_settings), 30f, R.color.text, bold = true))
        root.addView(
            ui.text(getString(R.string.home_subtitle), 13f, R.color.text_sub).apply {
                setPadding(0, ui.dp(7), 0, 0)
            }
        )
        root.addView(ui.gap(20))

        val master = ui.glassCard()
        master.addView(buildSwitchRow(
            getString(R.string.ui_switch_enable), null, ModulePrefs.isEnabled(prefs)
        ) { value ->
            prefs.edit().putBoolean(ModulePrefs.KEY_ENABLED, value).apply()
            toast(getString(if (value) R.string.ui_enable_on else R.string.ui_enable_off))
        })
        master.addView(ui.divider())
        master.addView(buildSwitchRow(
            getString(R.string.ui_switch_hide),
            getString(R.string.ui_switch_hide_hint),
            ModulePrefs.isHideIcon(prefs)
        ) { value ->
            setLauncherIconHidden(value)
            prefs.edit().putBoolean(ModulePrefs.KEY_HIDE_ICON, value).apply()
            toast(getString(if (value) R.string.ui_hide_on else R.string.ui_hide_off))
        })
        root.addView(master)
        Motion.fadeInUp(master, 0L)

        root.addView(ui.gap(14))

        val shortcuts = ui.glassCard()
        shortcuts.addView(
            ui.row(R.drawable.ic_open, getString(R.string.ui_btn_lsposed), null) { openLsposed() }
        )
        root.addView(shortcuts)
        Motion.fadeInUp(shortcuts, 60L)

        root.addView(ui.gap(14))

        val about = ui.glassCard()
        about.addView(
            ui.row(R.drawable.ic_person, getString(R.string.ui_title_author),
                getString(R.string.ui_sub_author)) {
                showSheet(getString(R.string.ui_title_author), getString(R.string.ui_author_body))
            }
        )
        about.addView(ui.divider())
        about.addView(
            ui.row(R.drawable.ic_heart, getString(R.string.ui_title_free),
                getString(R.string.ui_sub_free)) {
                showSheet(getString(R.string.ui_title_free), getString(R.string.ui_free_body))
            }
        )
        about.addView(ui.divider())
        about.addView(
            ui.row(R.drawable.ic_star, getString(R.string.ui_title_update),
                getString(R.string.ui_sub_update)) { openProjectPage() }
        )
        root.addView(about)
        Motion.fadeInUp(about, 120L)

        return root
    }

    /** 开关行：整行可点，不只是点开关 */
    private fun buildSwitchRow(
        title: String,
        subtitle: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val row = ui.row(null, title, subtitle, null)
        val sw = GlassSwitch(this).apply {
            setChecked(checked, animate = false)
            onCheckedChange = { value -> onChange(value) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.setChecked(!sw.isCheckedState()); onChange(sw.isCheckedState()) }
        return row
    }

    private fun showSheet(title: String, body: String) {
        startActivity(
            Intent(this, SheetActivity::class.java)
                .putExtra(SheetActivity.EXTRA_TITLE, title)
                .putExtra(SheetActivity.EXTRA_BODY, body)
        )
    }

    /** 项目主页：模块本体不联网，这里只是把链接交给系统浏览器 */
    private fun openProjectPage() {
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)))
        }.isSuccess
        if (!opened) toast(getString(R.string.ui_msg_lsposed))
    }

    private fun syncLauncherIcon() {
        setLauncherIconHidden(ModulePrefs.isHideIcon(prefs))
    }

    private companion object {
        private const val PROJECT_URL = "https://github.com/2979208016/AstraGalaxy"
    }
}
