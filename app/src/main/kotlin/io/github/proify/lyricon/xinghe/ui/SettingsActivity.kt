package io.github.proify.lyricon.xinghe.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 星河功能说明页。
 *
 * 星河是星流的 LyricON「歌词提供者」，不使用开关管理功能，本页仅说明它提供的能力：
 * 各主流音乐平台的实时歌词统一由星河按 LyricON 协议推送，星流原生「胶囊歌词」负责呈现。
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }
        scroll.addView(root)
        scroll.setBackgroundColor(Color.parseColor("#0E1116"))
        setContentView(scroll)

        header(root)

        section(root, "模块定位")
        desc(root, "星河是星流（AstraFlow）的 LyricON「歌词提供者」。它只负责向星流实时提供歌词，由星流原生「胶囊歌词」负责在流体云岛呈现。星河自身不含任何开关或界面配置。")
        gap(root, 16)

        section(root, "提供的能力")
        feature(root, "音乐歌词", "为星流提供实时歌词：MeloYou 原生接入；网易云、QQ 音乐、酷狗、酷我、Spotify、汽水、波点、LX、Poweramp、小米、OPPO 等主流平台通过在线匹配补全。")
        gap(root, 16)

        section(root, "使用说明")
        bullet(root, "在星流中开启「胶囊歌词」，即可让星河提供的歌词上岛。")
        bullet(root, "播放音乐时，星河自动识别媒体源并推送歌词。")
        bullet(root, "以上能力随星流常驻，无需在本页配置。")
        gap(root, 16)

        section(root, "关于")
        desc(root, "免费公益 · 创作者 yiyi · QQ 2979208016", Color.parseColor("#8A94A6"))
    }

    // ---------------- UI 构建 ----------------

    private fun header(root: LinearLayout) {
        label(root, "星河", 26f, true, Color.WHITE)
        gap(root, 4)
        label(root, "星流歌词提供者 · v" + versionName(), 14f, false, Color.parseColor("#7CF5B0"))
        gap(root, 20)
    }

    private fun section(root: LinearLayout, text: String) {
        label(root, text, 18f, true, Color.WHITE)
        gap(root, 6)
    }

    private fun feature(root: LinearLayout, title: String, body: String) {
        label(root, title, 15f, true, Color.parseColor("#7CE7FF"))
        gap(root, 4)
        desc(root, body, Color.parseColor("#B0BEC5"))
        gap(root, 12)
    }

    private fun bullet(root: LinearLayout, text: String) {
        root.addView(TextView(this).apply {
            this.text = "·  $text"
            textSize = 14f
            setTextColor(Color.parseColor("#B0BEC5"))
            gravity = Gravity.START
            setLineSpacing(dp(3).toFloat(), 1.0f)
        })
        gap(root, 6)
    }

    private fun desc(root: LinearLayout, text: String, color: Int = Color.parseColor("#B0BEC5")) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(color)
            gravity = Gravity.START
            setLineSpacing(dp(3).toFloat(), 1.0f)
        })
    }

    private fun label(root: LinearLayout, text: String, sizeSp: Float, bold: Boolean, color: Int) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            gravity = Gravity.START
            if (bold) typeface = Typeface.DEFAULT_BOLD
        })
    }

    private fun gap(root: LinearLayout, dpValue: Int) {
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(dpValue))
        })
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrElse { "?" } ?: "?"

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
