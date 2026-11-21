package io.github.proify.astrafirmament.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.proify.astrafirmament.BuildConfig
import io.github.proify.astrafirmament.R
import io.github.proify.astrafirmament.ui.icons.AstraGlyphs

/**
 * 关于页（9.1）：只留三样东西——项目卡片、一个「关于项目」文档入口、赞助作者。
 * 如实说明 / 免责声明 / 前提条件等长文案全部收进「关于项目」文档页（可复制），
 * 这里不再平铺。
 */
@Composable
fun AboutPage(onOpenAbout: () -> Unit = {}) {
    var sponsorOpen by remember { mutableStateOf(false) }

    AppTabPage(title = "关于", subtitle = "AstraFirmament · 星河岛上的歌词与上岛能力") {
        group("项目") {
            ValueRow(title = "版本", value = BuildConfig.VERSION_NAME, icon = AstraGlyphs.Info)
            NoteRow("LSPosed 模块：歌词胶囊 + 智能上岛，界面基于 COUI 凝光组件库。")
        }
        group("说明") {
            LinkRow(
                title = "关于项目",
                summary = "读取了什么、Root 用途、日志策略、前提条件与免责声明",
                value = "查看",
                icon = AstraGlyphs.Mail,
                onClick = onOpenAbout,
            )
        }
        sponsorSection(sponsorOpen) { sponsorOpen = !sponsorOpen }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sponsorSection(
    open: Boolean,
    onToggle: () -> Unit,
) {
    group("赞助作者") {
        LinkRow(
            title = "赞助作者",
            summary = "如果它帮到了你，可以扫码支持一下",
            value = if (open) "收起" else "展开",
            icon = AstraGlyphs.Gift,
            onClick = onToggle,
        )
        if (open) {
            NoteRow("支付宝扫码即可；不赞助完全不影响使用。")
        }
    }
    if (open) {
        item {
            Image(
                painter = painterResource(R.drawable.alipay_qr),
                contentDescription = "支付宝收款码",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
        }
    }
}
