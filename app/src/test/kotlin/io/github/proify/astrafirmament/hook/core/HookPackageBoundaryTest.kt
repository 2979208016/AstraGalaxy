package io.github.proify.astrafirmament.hook.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 架构边界测试（规范第 6 条）：hook/ 目录不得引用 App 进程专属符号。
 *
 * 只禁止 App 进程专属符号（Activity/Compose/R.*）——不禁止一切外部引用
 * （Hook 类天然要引用 android.* 框架与目标 App 的类）。
 * 本仓库禁本地构建：测试随包交付，CI 环境执行。
 */
class HookPackageBoundaryTest {

    private val hookRoot = File("src/main/kotlin/io/github/proify/astrafirmament/hook")

    @Test fun `hook 目录不引用 App 进程专属符号`() {
        val banned = listOf(
            Regex("""import\s+io\.github\.proify\.lyricon\.xinghe\.ui\."""),
            Regex("""import\s+io\.github\.proify\.lyricon\.xinghe\.app\.IslandColdStartActivity"""),
            Regex("""import\s+androidx\.compose\."""),
            Regex("""R\.(drawable|string|layout|id|mipmap)""")
        )
        // 特例白名单：Hook 逻辑启动自家组件（透明页/服务）用的是【字符串组件名】
        // 而非 import——本测试只管 import 层，字符串组件名属框架约定路径放行。
        val violations = mutableListOf<String>()
        hookRoot.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                banned.forEach { re ->
                    if (re.containsMatchIn(line)) {
                        violations.add("${f.name}:${i + 1}: $line".trim())
                    }
                }
            }
        }
        assertTrue(
            "hook/ 引用了 App 进程专属符号:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
