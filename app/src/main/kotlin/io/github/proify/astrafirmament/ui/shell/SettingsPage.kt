package io.github.proify.astrafirmament.ui.shell

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.itos.coui.layout.Divider
import io.github.proify.astrafirmament.BuildConfig
import io.github.proify.astrafirmament.settings.ModulePrefs
import io.github.proify.astrafirmament.ui.icons.AstraGlyphs

/** 设置页：模块级开关、运行状态与工具。 */
@Composable
fun SettingsPage(onOpenPermission: () -> Unit, onOpenDev: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ModulePrefs.of(context) }
    var enabled by remember { mutableStateOf(ModulePrefs.isEnabled(prefs)) }
    var hideIcon by remember { mutableStateOf(ModulePrefs.isHideIcon(prefs)) }

    AppTabPage(title = "设置", subtitle = "模块开关、运行状态与工具") {
        group("模块") {
            SwitchRow(
                title = "启用模块",
                summary = "关掉后所有上岛能力与歌词提供都停止",
                icon = AstraGlyphs.Gear,
                checked = enabled,
                onCheckedChange = { want ->
                    enabled = want
                    ModulePrefs.edit(context).putBoolean(ModulePrefs.KEY_ENABLED, want).apply()
                },
            )
            Divider()
            SwitchRow(
                title = "隐藏桌面图标",
                summary = "隐藏后可通过桌面快捷方式进入",
                icon = AstraGlyphs.PasswordHidden,
                checked = hideIcon,
                onCheckedChange = { want ->
                    hideIcon = want
                    ModulePrefs.edit(context).putBoolean(ModulePrefs.KEY_HIDE_ICON, want).apply()
                    // Compose 壳不经过 Activity，桌面图标的 alias 状态要在这里直接改，
                    // 否则开关只是写了个偏好、图标毫无变化。
                    runCatching {
                        context.packageManager.setComponentEnabledSetting(
                            ComponentName(context, "${context.packageName}.ui.LauncherAlias"),
                            if (want) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP,
                        )
                    }
                },
            )
        }
        group("运行状态") {
            ValueRow(title = "模块版本", value = BuildConfig.VERSION_NAME, icon = AstraGlyphs.Info)
            Divider()
            LinkRow(
                title = "权限中心",
                value = "Root 与 LSPosed",
                icon = AstraGlyphs.Lock,
                onClick = onOpenPermission,
            )
        }
        group("工具") {
            LinkRow(title = "开发者选项", icon = AstraGlyphs.Wrench, onClick = onOpenDev)
        }
    }
}
