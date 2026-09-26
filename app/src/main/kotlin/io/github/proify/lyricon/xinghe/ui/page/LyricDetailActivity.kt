package io.github.proify.lyricon.xinghe.ui.page

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import io.github.proify.lyricon.xinghe.R
import io.github.proify.lyricon.xinghe.settings.ModulePrefs
import io.github.proify.lyricon.xinghe.ui.widget.GlassSwitch
import io.github.proify.lyricon.xinghe.ui.widget.Motion
import io.github.proify.lyricon.xinghe.ui.widget.UIKit

/**
 * 歌词胶囊详情页。
 *
 * 以前这块占了整个设置页的篇幅，现在缩成一张卡的状态 + 一节说明，
 * 把主位让给「星河是个插件」这件事。
 */
class LyricDetailActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UIKit(this)
        setTheme(R.style.Theme_LyricProvider_Detail)
        setContentView(wrapPage(scrollable(buildContent())))
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ui.dp(40))
        }

        root.addView(Toolbar.build(this, ui, getString(R.string.lyric_detail_title)) { finish() })
        root.addView(ui.gap(10))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), 0, ui.dp(18), 0)
        }
        root.addView(body)

        val prefs = ModulePrefs.of(this)
        val on = ModulePrefs.isEnabled(prefs) && ModulePrefs.isLyricEnabled(prefs)

        // 状态卡：一行状态点 + 一句话 + 开关
        val statusCard = ui.glassCard()
        statusCard.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusRow.addView(
            ui.statusDot(if (on) R.color.ok else R.color.off).apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(8), ui.dp(8))
            }
        )
        statusRow.addView(
            ui.text(
                getString(if (on) R.string.lyric_status_ready else R.string.lyric_status_off),
                14f, if (on) R.color.ok else R.color.text_hint
            ).apply { setPadding(ui.dp(10), 0, 0, 0) },
            ui.weight()
        )
        val toggle = GlassSwitch(this).apply {
            setChecked(on, animate = false)
            onCheckedChange = { value ->
                prefs.edit().putBoolean(ModulePrefs.KEY_LYRIC, value).apply()
            }
        }
        statusRow.addView(toggle)
        statusCard.addView(statusRow)
        body.addView(statusCard)
        Motion.fadeInUp(statusCard, 0L)

        body.addView(ui.gap(14))

        // 适配应用列表
        val appsCard = ui.glassCard()
        appsCard.addView(
            ui.row(null, getString(R.string.lyric_supported), null) {
                startActivity(
                    android.content.Intent(this, SheetActivity::class.java)
                        .putExtra(SheetActivity.EXTRA_TITLE, getString(R.string.ui_title_apps))
                        .putExtra(SheetActivity.EXTRA_BODY, getString(R.string.ui_apps_body))
                )
            }
        )
        body.addView(appsCard)
        Motion.fadeInUp(appsCard, 60L)

        body.addView(ui.gap(14))

        // 怎么用：只留一行小字
        val howto = ui.text(getString(R.string.lyric_howto_body), 12f, R.color.text_hint).apply {
            setLineSpacing(ui.dp(5).toFloat(), 1.12f)
        }
        body.addView(howto)
        Motion.fadeInUp(howto, 120L)

        return root
    }
}
