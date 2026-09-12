package io.github.proify.lyricon.xinghe.xposed

/**
 * 星河模块常量。
 *
 * 星河的唯一定位：星流（AstraFlow）的 LyricON「歌词提供者」。
 * 它把播放器已有的歌词按 LyricON 协议推送给星流，由星流原生「胶囊歌词」
 * 负责最终呈现，星河自身不提供任何开关、入口或渲染。
 *
 * 包名以 io.github.proify.lyricon 开头，星流按 LyricON 协议自动识别歌词提供者。
 */
object Constants {
    /** 本模块（LyricON Provider 提供端）包名 */
    const val PROVIDER_PACKAGE_NAME: String = "io.github.proify.lyricon.xinghe"

    /** 被 hook 的播放器包名：MeloYou */
    const val PLAYER_PACKAGE_NAME: String = "com.music"

    /** MeloYou 写入当前歌词的文件名（应用私有 files 目录） */
    const val SONG_LYRIC_FILE: String = "songLyric.json"

    /** MeloYou 写入当前歌曲信息的文件名（应用私有 files 目录） */
    const val NOW_PLAYING_FILE: String = "nowPlaying.json"

    // ---------------- 本地歌词缓存配方 ----------------

    /**
     * 各平台的本地歌词缓存位置与格式。
     *
     * 全部离线读取，不发起任何网络请求；找不到缓存就什么也不推。
     * 路径均相对于宿主 App 自己的目录（externalFiles / files / cache），
     * 因此模块运行在目标 App 进程内即可直接读取，无需 root。
     *
     * ext 为空表示目录内所有文件都尝试解析（如网易云缓存文件就是纯数字名）。
     */
    internal val LOCAL_RECIPES: List<LocalRecipe> = listOf(
        LocalRecipe(
            packageName = "com.kugou.android",           // 酷狗音乐
            sources = listOf(
                LocalSource(BaseDir.EXTERNAL_FILES, "kugou/lyrics", LyricFormat.KRC, listOf("krc"))
            )
        ),
        LocalRecipe(
            packageName = "com.kugou.android.lite",      // 酷狗概念版
            sources = listOf(
                LocalSource(BaseDir.EXTERNAL_FILES, "kugou/lyrics", LyricFormat.KRC, listOf("krc"))
            )
        ),
        LocalRecipe(
            packageName = "cn.wenyu.bodian",             // 波点音乐
            sources = listOf(
                LocalSource(BaseDir.CACHE, "lyric", LyricFormat.LRC, listOf("lrcx", "lrc"))
            )
        ),
        LocalRecipe(
            packageName = "com.tencent.qqmusic",         // QQ 音乐
            sources = listOf(
                LocalSource(BaseDir.EXTERNAL_FILES, "qqmusic/qrc", LyricFormat.QRC, listOf("qrc"))
            )
        ),
        LocalRecipe(
            packageName = "com.netease.cloudmusic",      // 网易云音乐
            sources = listOf(
                LocalSource(BaseDir.EXTERNAL_FILES, "LrcCache", LyricFormat.NETEASE)
            )
        ),
        LocalRecipe(
            packageName = "com.heytap.music",            // 欢太（OPPO）音乐
            sources = listOf(
                LocalSource(BaseDir.EXTERNAL_FILES, "lyric", LyricFormat.LRC, listOf("alm3ll", "lrc"))
            )
        )
    )

    /** 走本地歌词通道的包名集合 */
    val LOCAL_PLAYER_PACKAGES: Set<String> = LOCAL_RECIPES.map { it.packageName }.toSet()

    internal fun recipeOf(packageName: String): LocalRecipe? =
        LOCAL_RECIPES.firstOrNull { it.packageName == packageName }

    /** 模块 Logo（SVG 星河），用于词幕端展示 */
    const val ICON: String =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\">" +
            "<rect width=\"108\" height=\"108\" rx=\"24\" fill=\"#0B1026\"/>" +
            "<path d=\"M20 62 L34 52 L42 60 L88 34 L74 68 L62 66 L50 82 Z\" fill=\"#7CE7FF\"/>" +
            "<circle cx=\"46\" cy=\"38\" r=\"4\" fill=\"#FFE9A8\"/>" +
            "<circle cx=\"30\" cy=\"74\" r=\"2.6\" fill=\"#9C8CFF\"/>" +
            "<circle cx=\"84\" cy=\"70\" r=\"2.2\" fill=\"#7CF5B0\"/>" +
            "<circle cx=\"66\" cy=\"26\" r=\"1.8\" fill=\"#FFFFFF\"/>" +
            "</svg>"
}
