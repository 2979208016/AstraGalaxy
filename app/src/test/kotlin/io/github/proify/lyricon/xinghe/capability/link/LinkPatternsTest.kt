package io.github.proify.astrafirmament.capability.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LinkPatterns 纯逻辑单测（shared 层——规范第 6 条 JUnit）。
 * CI 环境执行；本仓库禁本地构建，测试代码随包交付。
 */
class LinkPatternsTest {

    @Test fun `完整 http 链接识别`() {
        val r = LinkPatterns.firstLink("看这个 https://github.com/xinghe/project 很好用")
        assertEquals("https://github.com/xinghe/project", r)
    }

    @Test fun `裸域名补 https 前缀`() {
        val r = LinkPatterns.firstLink("去 b23.tv/abc123 看视频")
        assertEquals("https://b23.tv/abc123", r)
    }

    @Test fun `无链接返回 null`() {
        assertEquals(null, LinkPatterns.firstLink("这段话里什么都没有"))
    }

    @Test fun `文件名不上岛`() {
        assertEquals(null, LinkPatterns.firstLink("build.gradle.kts"))
    }

    @Test fun `手机号识别纯数字`() {
        val r = LinkPatterns.firstPhone("联系 13800138000 谢谢")
        assertEquals("13800138000", r)
    }

    @Test fun `带分隔的手机号`() {
        val r = LinkPatterns.firstPhone("电话 138-0013-8000")
        assertTrue(r != null)
    }

    @Test fun `非手机号不识别`() {
        assertEquals(null, LinkPatterns.firstPhone("订单号 12345678"))
    }
}
