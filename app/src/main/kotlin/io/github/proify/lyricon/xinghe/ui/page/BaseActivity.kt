package io.github.proify.lyricon.xinghe.ui.page

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.github.proify.lyricon.xinghe.R
import io.github.proify.lyricon.xinghe.settings.ModulePrefs
import io.github.proify.lyricon.xinghe.ui.widget.Motion
import io.github.proify.lyricon.xinghe.ui.widget.UIKit

/**
 * 所有页面的公共壳：顶部状态栏留白 + 内容区。
 * 提供 dp / toast / 跳转 / 桌面图标开关等公共能力。
 */
abstract class BaseActivity : Activity() {

    protected lateinit var ui: UIKit

    /** 顶部给状态栏留出的空间，页面自己决定要不要画光晕 */
    protected fun wrapPage(content: View, glow: Boolean = true): View {
        val root = FrameLayout(this)

        if (glow) {
            // 顶部一层很淡的径向光晕，缓慢呼吸，让页面「有空气感」
            root.addView(
                View(this).apply {
                    background = getDrawable(R.drawable.bg_glow)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(520)
                    ).apply { gravity = Gravity.TOP }
                }
            )
            Motion.breathe(root.getChildAt(0), minAlpha = 0.6f, periodMs = 4200L)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.setPadding(0, statusBarHeight(), 0, 0)
        container.addView(content)
        root.addView(container)
        return root
    }

    protected fun scrollable(inner: LinearLayout): ScrollView =
        ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(color(R.color.bg))
            addView(inner)
        }

    protected fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val base = if (id > 0) resources.getDimensionPixelSize(id) else ui.dp(24)
        return base
    }

    protected fun color(res: Int): Int = getColor(res)

    protected fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    protected fun openPage(cls: Class<*>) {
        startActivity(Intent(this, cls))
    }

    /** 跳到 LSPosed 管理器（拿不到就退回显式组件） */
    protected fun openLsposed() {
        val manager = "org.lsposed.manager"
        val launcher = packageManager.getLaunchIntentForPackage(manager)
        if (launcher != null) {
            runCatching { startActivity(launcher) }
                .onFailure { toast(getString(R.string.ui_need_lsposed)) }
            return
        }
        val explicit = Intent().setComponent(
            ComponentName(manager, "org.lsposed.manager.ui.activity.MainActivity")
        )
        runCatching { startActivity(explicit) }
            .onFailure { toast(getString(R.string.ui_need_lsposed)) }
    }

    /** 桌面 alias 与开关保持一致 */
    protected fun setLauncherIconHidden(hidden: Boolean) {
        val alias = ComponentName(this, "$packageName.ui.LauncherAlias")
        val state = if (hidden) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        runCatching {
            packageManager.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
        }
    }

    protected fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
}
