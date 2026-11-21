package io.github.proify.astrafirmament.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 应用界面用的图标，按用处起名；颜色由使用处上色。
 * 功能类图标取自自绘的 [AstraDrawnGlyphs]；箭头、对勾、关闭这类控件小记号
 * 在本文件内自绘（9.0 起不再依赖上游图标库）。
 */
object AstraGlyphs {
    // ── 控件里的小记号（自绘） ──
    val ChevronRight: ImageVector get() = ControlGlyphs.ChevronRight
    val ChevronLeft: ImageVector get() = ControlGlyphs.ChevronLeft
    val Close: ImageVector get() = ControlGlyphs.Close
    val Check: ImageVector get() = ControlGlyphs.Check
    val Sort: ImageVector get() = ControlGlyphs.Sort
    val Wifi: ImageVector get() = ControlGlyphs.Wifi
    val Bluetooth: ImageVector get() = ControlGlyphs.Bluetooth
    val PasswordHidden: ImageVector get() = ControlGlyphs.PasswordHidden

    // ── 功能、底栏图标（自绘一套） ──
    val Music: ImageVector get() = AstraDrawnGlyphs.Music
    val Message: ImageVector get() = AstraDrawnGlyphs.Message
    val LiveActivity: ImageVector get() = AstraDrawnGlyphs.LiveActivity
    val ExternalApps: ImageVector get() = AstraDrawnGlyphs.ExternalApps
    val Move: ImageVector get() = AstraDrawnGlyphs.Move
    val Lock: ImageVector get() = AstraDrawnGlyphs.Lock
    val Launch: ImageVector get() = AstraDrawnGlyphs.Launch
    val Gamepad: ImageVector get() = AstraDrawnGlyphs.Gamepad
    val Record: ImageVector get() = AstraDrawnGlyphs.Record
    val TabSystem: ImageVector get() = AstraDrawnGlyphs.TabSystem
    val Search: ImageVector get() = AstraDrawnGlyphs.Search
    val Mail: ImageVector get() = AstraDrawnGlyphs.Mail
    val Gift: ImageVector get() = AstraDrawnGlyphs.Gift
    val Gear: ImageVector get() = AstraDrawnGlyphs.Gear
    val Wrench: ImageVector get() = AstraDrawnGlyphs.Wrench
    val Info: ImageVector get() = AstraDrawnGlyphs.Info
    val Bell: ImageVector get() = AstraDrawnGlyphs.Bell
    val Warning: ImageVector get() = AstraDrawnGlyphs.Warning
    val Sparkle: ImageVector get() = AstraDrawnGlyphs.Sparkle
}
