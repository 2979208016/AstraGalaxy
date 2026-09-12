package io.github.proify.lyricon.xinghe.xposed

/**
 * 星河模块常量。
 *
 * 星河是星流（AstraFlow）的 LyricON「歌词提供者」：只把播放器**本地已有的**
 * 歌词按 LyricON 协议推给星流，由星流原生「胶囊歌词」呈现。全程离线，不联网。
 */
object Constants {

    /** 本模块（LyricON Provider 提供端）包名 */
    const val PROVIDER_PACKAGE_NAME: String = "io.github.proify.lyricon.xinghe"

    /** 远程偏好（供设置页与目标进程共享） */
    const val PREFS_NAME: String = "xinghe_settings"
    const val KEY_ENABLED: String = "module_enabled"
    const val KEY_HIDE_ICON: String = "hide_launcher_icon"

    /** MeloYou：由 XingHeLyricProvider 处理（它会把当前歌词落到私有文件） */
    const val PLAYER_PACKAGE_NAME: String = "com.music"
    const val SONG_LYRIC_FILE: String = "songLyric.json"
    const val NOW_PLAYING_FILE: String = "nowPlaying.json"

    // ---------------- 本地歌词缓存配方 ----------------

    /**
     * 各平台的本地歌词缓存位置与格式。
     *
     * 同一平台往往同时存在「正式目录」与「临时目录」（例如酷狗写作
     * `kugou/temp_lyrics`，播完才挪到 `kugou/lyrics`），也可能同时存在
     * 内部存储与外部存储两份，因此每个平台可以配置多个 [LocalSource]。
     *
     * ext 为空表示目录内所有文件都尝试解析（网易云缓存文件名就是纯数字）。
     */
    internal val LOCAL_RECIPES: List<LocalRecipe> = listOf(
        LocalRecipe(
            packageName = "com.kugou.android",
            displayName = "酷狗音乐",
            sources = kugouSources()
        ),
        LocalRecipe(
            packageName = "com.kugou.android.lite",
            displayName = "酷狗概念版",
            sources = kugouSources()
        ),
        LocalRecipe(
            packageName = "cn.wenyu.bodian",
            displayName = "波点音乐",
            sources = listOf(
                LocalSource(BaseDir.CACHE, "lyric", LyricFormat.LRC, listOf("lrcx", "lrc")),
                LocalSource(BaseDir.EXTERNAL_CACHE, "lyric", LyricFormat.LRC, listOf("lrcx", "lrc"))
            )
        ),
        LocalRecipe(
            packageName = "com.tencent.qqmusic",
            displayName = "QQ 音乐",
            sources = listOf(
                LocalSource(BaseDir.EXTERNAL_FILES, "qqmusic/qrc", LyricFormat.QRC, listOf("qrc")),
                LocalSource(BaseDir.FILES, "qqmusic/qrc", LyricFormat.QRC, listOf("qrc"))
            )
        ),
        LocalRecipe(
            packageName = "com.netease.cloudmusic",
            displayName = "网易云音乐",
            sources = listOf(
                LocalSource(BaseDir.EXTERNAL_FILES, "LrcCache", LyricFormat.NETEASE),
                LocalSource(BaseDir.FILES, "LrcCache", LyricFormat.NETEASE)
            )
        ),
        LocalRecipe(
            packageName = "com.heytap.music",
            displayName = "OPPO 音乐",
            sources = listOf(
                LocalSource(BaseDir.FILES, "lyric", LyricFormat.LRC, listOf("alm3ll", "lrc")),
                LocalSource(BaseDir.EXTERNAL_FILES, "lyric", LyricFormat.LRC, listOf("alm3ll", "lrc")),
                LocalSource(BaseDir.CACHE, "lyric", LyricFormat.LRC, listOf("alm3ll", "lrc"))
            )
        )
    )

    /** 酷狗系：正式歌词目录 + 临时歌词目录，内部与外部存储都查 */
    private fun kugouSources(): List<LocalSource> = listOf(
        LocalSource(BaseDir.EXTERNAL_FILES, "kugou/lyrics", LyricFormat.KRC, listOf("krc")),
        LocalSource(BaseDir.EXTERNAL_FILES, "kugou/temp_lyrics", LyricFormat.KRC, listOf("krc")),
        LocalSource(BaseDir.FILES, "kugou/lyrics", LyricFormat.KRC, listOf("krc")),
        LocalSource(BaseDir.FILES, "kugou/temp_lyrics", LyricFormat.KRC, listOf("krc"))
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
