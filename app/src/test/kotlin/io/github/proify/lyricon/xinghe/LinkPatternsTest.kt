package io.github.proify.lyricon.xinghe

import io.github.proify.lyricon.xinghe.capability.link.LinkPatterns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkPatternsTest {

    @Test
    fun fullUrl() {
        assertEquals("https://www.example.com/a?b=1",
            LinkPatterns.firstLink("看看这个 https://www.example.com/a?b=1 挺好"))
    }

    @Test
    fun httpKept() {
        assertEquals("http://a.cn/x", LinkPatterns.firstLink("http://a.cn/x"))
    }

    @Test
    fun bareDomain() {
        assertEquals("https://github.com/x/y",
            LinkPatterns.firstLink("地址 github.com/x/y"))
    }

    @Test
    fun wwwPrefixed() {
        assertEquals("https://www.bilibili.com/video/BV1",
            LinkPatterns.firstLink("分享 www.bilibili.com/video/BV1"))
    }

    @Test
    fun trailingPunctuationStripped() {
        assertEquals("https://a.com/b", LinkPatterns.firstLink("看 https://a.com/b。"))
    }

    @Test
    fun chineseCommaStripped() {
        assertEquals("https://a.com/b", LinkPatterns.firstLink("链接：https://a.com/b，然后"))
    }

    @Test
    fun sourceCodeNotLink() {
        assertNull(LinkPatterns.firstLink("MainActivity.kt"))
        assertNull(LinkPatterns.firstLink("build.gradle.kts"))
    }

    @Test
    fun plainTextNull() {
        assertNull(LinkPatterns.firstLink("今天天气不错"))
        assertNull(LinkPatterns.firstLink(""))
        assertNull(LinkPatterns.firstLink(null))
    }

    @Test
    fun hostExtraction() {
        assertEquals("bilibili.com", LinkPatterns.hostOf("https://www.bilibili.com/video/x"))
    }

    @Test
    fun hasLink() {
        assertEquals(true, LinkPatterns.hasLink("去 https://a.cn"))
        assertEquals(false, LinkPatterns.hasLink("没有链接"))
    }

    @Test
    fun shortLink() {
        assertEquals("https://b23.tv/xYz", LinkPatterns.firstLink("视频在 b23.tv/xYz"))
    }

    @Test
    fun wechatShare() {
        assertEquals("https://mp.weixin.qq.com/s/abc123",
            LinkPatterns.firstLink("【分享】给你看一篇文章 https://mp.weixin.qq.com/s/abc123 复制打开"))
    }

    @Test
    fun fullWidthParen() {
        assertEquals("https://a.com/b", LinkPatterns.firstLink("看这里（https://a.com/b）吧"))
    }

    @Test
    fun chineseAroundDomain() {
        assertEquals("https://github.com/x/y",
            LinkPatterns.firstLink("地址是github.com/x/y然后就这样"))
    }

    @Test
    fun multiLine() {
        val text = "第一行没链接\n第二行也没有\n最后一行 https://www.bilibili.com/video/BV1xx411c7mD 就这个"
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", LinkPatterns.firstLink(text))
    }

    @Test
    fun multipleLinksReturnsFirst() {
        assertEquals("https://a.com/1", LinkPatterns.firstLink("第一个 https://a.com/1 第二个 https://b.com/2"))
    }

    @Test
    fun ipAndPort() {
        assertEquals("https://192.168.1.1:8080/admin",
            LinkPatterns.firstLink("后台 https://192.168.1.1:8080/admin 进"))
    }

    @Test
    fun emailNotLink() {
        // 邮箱不作为链接上岛
        assertNull(LinkPatterns.firstLink("联系 admin@example.com 就行"))
    }
}
