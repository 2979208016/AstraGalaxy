package io.github.proify.astrafirmament.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.itos.coui.theme.CouiTheme

/**
 * 应用外观：COUI（ColorOS 凝光）官方预设，深浅色跟随系统。
 * 9.0 起移除 hig/iOS 主题层与全部自定义外观预设，只保留这一套。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    CouiTheme(darkTheme = isSystemInDarkTheme(), content = content)
}
