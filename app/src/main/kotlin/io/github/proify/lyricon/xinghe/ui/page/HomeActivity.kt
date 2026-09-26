package io.github.proify.lyricon.xinghe.ui.page

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import io.github.proify.lyricon.xinghe.R
import io.github.proify.lyricon.xinghe.capability.link.LinkHub
import io.github.proify.lyricon.xinghe.settings.ModuleEnabledState
import io.github.proify.lyricon.xinghe.settings.ModulePrefs
import io.github.proify.lyricon.xinghe.ui.widget.CapabilityCard
import io.github.proify.lyricon.xinghe.ui.widget.Motion
import io.github.proify.lyricon.xinghe.ui.widget.UIKit

/**
 * 首页：能力卡列表。
 * 每张卡就是一个能力，名字下面一行当前状态，点进去才是详情。
 * 所有说明都贴在详情页里，不再弹对话框。
 */
class HomeActivity : BaseActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UIKit(this)
        prefs = ModulePrefs.of(this)
        setContentView(wrapPage(scrollable(buildContent())))
    }

    override fun onResume() {
        super.onResume()
        // 每次进首页都连一次岛并刷新状态：用户先开界面再开链接助手时，
        // 也能立刻看到「已连接」，不用等下次重启进程。
        runCatching { LinkHub.warmUp(this) }
        setContentView(wrapPage(scrollable(buildContent())))
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), ui.dp(24), ui.dp(18), ui.dp(40))
        }

        header(root)
        if (!ModuleEnabledState.isModuleEnabled(this)) {
            root.addView(ui.gap(12))
            root.addView(
                ui.glassCard().apply {
                    setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
                    addView(
                        ui.text(
                            getString(R.string.home_not_active),
                            12f,
                            R.color.text_hint
                        ).apply { setLineSpacing(ui.dp(4).toFloat(), 1.12f) }
                    )
                }
            )
        }
        root.addView(ui.gap(20))

        val factory = CapabilityCard(ui, this)

        val lyricOn = ModulePrefs.isEnabled(prefs) && ModulePrefs.isLyricEnabled(prefs)
        val lyric = factory.build(
            iconRes = R.drawable.ic_note,
            tintRes = R.color.accent,
            title = getString(R.string.cap_lyric),
            subtitle = getString(if (lyricOn) R.string.cap_lyric_on else R.string.cap_lyric_off),
            checked = lyricOn,
            onToggle = { value ->
                prefs.edit().putBoolean(ModulePrefs.KEY_LYRIC, value).apply()
                toast(getString(if (value) R.string.ui_enable_on else R.string.ui_enable_off))
            },
            onOpen = { openPage(LyricDetailActivity::class.java) }
        )
        root.addView(lyric.root)
        Motion.fadeInUp(lyric.root, 0L)

        root.addView(ui.gap(14))

        val linkOn = ModulePrefs.isEnabled(prefs) && ModulePrefs.isLinkEnabled(prefs)
        val link = factory.build(
            iconRes = R.drawable.ic_open,
            tintRes = R.color.ok,
            title = getString(R.string.cap_link),
            subtitle = getString(if (linkOn) R.string.cap_link_on else R.string.cap_link_off),
            checked = linkOn,
            onToggle = { value ->
                prefs.edit().putBoolean(ModulePrefs.KEY_LINK, value).apply()
                toast(getString(if (value) R.string.ui_link_on else R.string.ui_link_off))
            },
            onOpen = { openPage(LinkDetailActivity::class.java) }
        )
        root.addView(link.root)
        Motion.fadeInUp(link.root, 60L)

        root.addView(ui.gap(14))
        val more = buildMoreCard()
        root.addView(more)
        Motion.fadeInUp(more, 120L)

        root.addView(ui.gap(26))

        val bottom = ui.glassCard()
        bottom.addView(
            ui.row(null, getString(R.string.home_settings), null) {
                openPage(SettingsActivity::class.java)
            }
        )
        bottom.addView(ui.divider(14))
        bottom.addView(
            ui.row(null, getString(R.string.home_about), null) { showAboutSheet() }
        )
        root.addView(bottom)
        Motion.fadeInUp(bottom, 180L)

        return root
    }

    private fun header(root: LinearLayout) {
        root.addView(ui.text(getString(R.string.app_name_launcher), 32f, R.color.text, bold = true))
        root.addView(
            ui.text(getString(R.string.home_subtitle), 14f, R.color.text_sub).apply {
                setPadding(0, ui.dp(7), 0, 0)
            }
        )

        val pills = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(14), 0, 0)
        }
        pills.addView(ui.pill("v${versionName()}"))
        val island = ui.pill(islandLabel(), islandColor()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = ui.dp(8) }
        }
        pills.addView(island)
        root.addView(pills)
    }

    /** 星河岛连接状态直接给出来，省得用户猜为什么没反应 */
    private fun islandLabel(): String {
        val state = LinkHub.stateName()
        val suffix = when (state) {
            "NOT_INSTALLED" -> "未安装星流"
            "WAITING" -> "未就绪"
            "REJECTED" -> "被拒绝"
            "READY" -> "已连接"
            else -> state
        }
        return "星河岛 $suffix"
    }

    private fun islandColor(): Int =
        if (LinkHub.isReady()) R.color.ok else R.color.text_hint

    private fun buildMoreCard(): View {
        val card = ui.glassCard()
        card.alpha = 0.5f
        card.setPadding(ui.dp(14), ui.dp(16), ui.dp(14), ui.dp(16))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ui.iconBadge(R.drawable.ic_star, R.color.text_hint))
        row.addView(
            ui.column(getString(R.string.cap_more), getString(R.string.cap_more_desc)),
            ui.weight()
        )
        card.addView(row)
        return card
    }

    private fun showAboutSheet() {
        startActivity(
            android.content.Intent(this, SheetActivity::class.java)
                .putExtra(SheetActivity.EXTRA_TITLE, getString(R.string.ui_title_about))
                .putExtra(SheetActivity.EXTRA_BODY, getString(R.string.ui_about_body))
        )
    }
}
