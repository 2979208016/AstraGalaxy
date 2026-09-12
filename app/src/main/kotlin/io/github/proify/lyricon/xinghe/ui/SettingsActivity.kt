package io.github.proify.lyricon.xinghe.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.proify.lyricon.xinghe.R
import io.github.proify.lyricon.xinghe.xposed.Constants

/**
 * 星河设置页。
 *
 * 结构对齐系统模块页：顶部标题 + 开关卡（启用模块 / 隐藏桌面图标 / 快捷按钮）
 * + 功能卡（支持的应用、歌词来源、匹配策略）+ 关于卡（关于、创作者、公益）。
 *
 * 页面上的功能描述与 xposed/Constants.kt 里的适配表一一对应，
 * 新增或移除平台时必须同步更新 strings.xml 中的说明文案。
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        setContentView(buildUi())
    }

    // ---------------- 页面骨架 ----------------

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(color(R.color.bg))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(40))
        }
        scroll.addView(root)

        header(root)
        switchCard(root)
        gap(root, 14)
        featureCard(root)
        gap(root, 14)
        aboutCard(root)
        return scroll
    }

    private fun header(root: LinearLayout) {
        root.addView(text("星河", 30f, true, color(R.color.text)))
        root.addView(
            text(getString(R.string.ui_subtitle), 14f, false, color(R.color.text_sub))
                .apply { setPadding(0, dp(6), 0, 0) }
        )
        val pill = text(
            getString(R.string.ui_version, versionName()),
            12f, false, color(R.color.accent)
        ).apply {
            background = getDrawable(R.drawable.bg_pill)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        wrap.addView(pill)
        root.addView(wrap)
    }

    private fun switchCard(root: LinearLayout) {
        val card = card(root)

        switchRow(
            card,
            R.drawable.ic_power,
            getString(R.string.ui_switch_enable),
            null,
            prefs.getBoolean(Constants.KEY_ENABLED, true)
        ) { checked ->
            prefs.edit().putBoolean(Constants.KEY_ENABLED, checked).apply()
            toast(getString(if (checked) R.string.ui_enable_on else R.string.ui_enable_off))
        }

        divider(card)

        switchRow(
            card,
            R.drawable.ic_hide,
            getString(R.string.ui_switch_hide),
            getString(R.string.ui_switch_hide_hint),
            prefs.getBoolean(Constants.KEY_HIDE_ICON, false)
        ) { checked ->
            setLauncherIconHidden(checked)
            prefs.edit().putBoolean(Constants.KEY_HIDE_ICON, checked).apply()
            toast(getString(if (checked) R.string.ui_hide_on else R.string.ui_hide_off))
        }

        divider(card)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        row.addView(
            actionButton(getString(R.string.ui_btn_lsposed), R.drawable.ic_open) { openLsposed() },
            weight()
        )
        row.addView(
            actionButton(getString(R.string.ui_btn_platforms), R.drawable.ic_apps) {
                showDialog(getString(R.string.ui_title_platforms), getString(R.string.ui_platforms_body))
            },
            weight()
        )
        card.addView(row)
    }

    private fun featureCard(root: LinearLayout) {
        val card = card(root)

        itemRow(
            card, R.drawable.ic_apps,
            getString(R.string.ui_title_platforms),
            getString(R.string.ui_sub_platforms, Constants.LOCAL_RECIPES.size + 1)
        ) { showDialog(getString(R.string.ui_title_platforms), getString(R.string.ui_platforms_body)) }

        divider(card)

        itemRow(
            card, R.drawable.ic_note,
            getString(R.string.ui_title_source),
            getString(R.string.ui_sub_source)
        ) { showDialog(getString(R.string.ui_title_source), getString(R.string.ui_source_body)) }

        divider(card)

        itemRow(
            card, R.drawable.ic_tune,
            getString(R.string.ui_title_match),
            getString(R.string.ui_sub_match)
        ) { showDialog(getString(R.string.ui_title_match), getString(R.string.ui_match_body)) }
    }

    private fun aboutCard(root: LinearLayout) {
        val card = card(root)

        itemRow(
            card, R.drawable.ic_info,
            getString(R.string.ui_title_about),
            getString(R.string.ui_sub_about)
        ) { showDialog(getString(R.string.ui_title_about), getString(R.string.ui_about_body)) }

        divider(card)

        itemRow(
            card, R.drawable.ic_person,
            getString(R.string.ui_title_author),
            getString(R.string.ui_sub_author)
        ) { showDialog(getString(R.string.ui_title_author), getString(R.string.ui_author_body)) }

        divider(card)

        itemRow(
            card, R.drawable.ic_heart,
            getString(R.string.ui_title_free),
            getString(R.string.ui_sub_free)
        ) { showDialog(getString(R.string.ui_title_free), getString(R.string.ui_free_body)) }
    }

    // ---------------- 组件 ----------------

    private fun card(root: LinearLayout): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        root.addView(wrap)
        return wrap
    }

    private fun itemRow(
        card: LinearLayout,
        iconRes: Int,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_row)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(icon(iconRes))
        row.addView(column(title, subtitle), weight())
        row.addView(icon(R.drawable.ic_chevron, R.color.text_hint))
        card.addView(row)
    }

    private fun switchRow(
        card: LinearLayout,
        iconRes: Int,
        title: String,
        subtitle: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        row.addView(icon(iconRes))
        row.addView(column(title, subtitle), weight())

        val sw = Switch(this).apply {
            isChecked = checked
            showText = false
            setOnCheckedChangeListener { _: CompoundButton, value: Boolean ->
                applySwitchTint(this, value)
                onChange(value)
            }
        }
        applySwitchTint(sw, checked)
        row.addView(sw)
        // 点击整行也可切换
        row.setOnClickListener {
            sw.isChecked = !sw.isChecked
        }
        card.addView(row)
    }

    private fun applySwitchTint(sw: Switch, checked: Boolean) {
        val on = color(R.color.accent)
        val off = color(R.color.divider)
        sw.thumbTintList = ColorStateList.valueOf(if (checked) on else color(R.color.text_hint))
        sw.trackTintList = ColorStateList.valueOf(if (checked) color(R.color.accent_soft) else off)
    }

    private fun actionButton(label: String, iconRes: Int, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_button)
            setPadding(dp(10), dp(13), dp(10), dp(13))
            isClickable = true
            setOnClickListener { onClick() }
        }
        box.addView(icon(iconRes))
        box.addView(
            text(label, 14f, false, color(R.color.text)).apply {
                setPadding(dp(8), 0, 0, 0)
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        return box
    }

    private fun column(title: String, subtitle: String?): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        col.addView(text(title, 16f, false, color(R.color.text)))
        if (!subtitle.isNullOrBlank()) {
            col.addView(
                text(subtitle, 12f, false, color(R.color.text_sub)).apply {
                    setPadding(0, dp(4), 0, 0)
                    setLineSpacing(dp(2).toFloat(), 1.0f)
                }
            )
        }
        return col
    }

    private fun icon(res: Int, tintRes: Int = R.color.icon): ImageView =
        ImageView(this).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(color(tintRes))
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }

    private fun divider(card: LinearLayout) {
        card.addView(
            View(this).apply {
                setBackgroundColor(color(R.color.divider))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { marginStart = dp(50); marginEnd = dp(14) }
            }
        )
    }

    private fun text(value: String, size: Float, bold: Boolean, colorInt: Int): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(colorInt)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }

    private fun gap(root: LinearLayout, value: Int) {
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(value))
        })
    }

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }

    // ---------------- 行为 ----------------

    private fun showDialog(title: String, message: String) {
        AlertDialog.Builder(this, R.style.Theme_LyricProvider_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.ui_dialog_ok, null)
            .show()
    }

    private fun openLsposed() {
        val manager = "org.lsposed.manager"
        val intent = packageManager.getLaunchIntentForPackage(manager)
        if (intent != null) {
            runCatching { startActivity(intent) }
                .onFailure { toast(getString(R.string.ui_need_lsposed)) }
        } else {
            toast(getString(R.string.ui_need_lsposed))
        }
    }

    private fun setLauncherIconHidden(hidden: Boolean) {
        val alias = ComponentName(this, "$packageName.ui.LauncherAlias")
        val state = if (hidden) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        runCatching {
            packageManager.setComponentEnabledSetting(
                alias, state, PackageManager.DONT_KILL_APP
            )
        }.onFailure { toast(it.message ?: "切换失败") }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"

    private fun color(res: Int): Int = getColor(res)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
