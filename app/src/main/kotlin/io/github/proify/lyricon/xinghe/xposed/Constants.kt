package io.github.proify.lyricon.xinghe.xposed

/**
 * 星河模块常量。
 *
 * 星河是星流（AstraFlow）的 LyricON「歌词提供者」：把播放器**自己已经拿到的**
 * 歌词按 LyricON 协议推给星流，由星流原生「胶囊歌词」呈现。
 * 优先读它缓存好的文件，读不到再只读旁路嗅探它刚收到的网络歌词。全程不改播放器行为。
 */
object Constants {

    /** 本模块（LyricON Provider 提供端）包名 */
    const val PROVIDER_PACKAGE_NAME: String = "io.github.proify.lyricon.xinghe"

    /** 诊断期：把 RN 桥上的候选字符串打到日志里 */
    const val VERBOSE_BRIDGE: Boolean = false

    /** 洛雪音乐助手 */
    const val LX_PACKAGE: String = "cn.toside.music.mobile"

    /** 汽水音乐：歌词在它自己的 NetCacheLoader 缓存里，按歌曲 id 定位（见 [LunaLyric]） */
    const val LUNA_PACKAGE: String = "com.luna.music"

    /** 酷我音乐 */
    const val KUWO_PACKAGE: String = "cn.kuwo.player"

    /** QQ 音乐：它的 ARTIST 字段是「歌名-歌手」结构（详见 PlayerTitle） */
    const val QQ_PACKAGE: String = "com.tencent.qqmusic"

    /**
     * 会把「歌名-歌手」写进 ARTIST 字段的播放器（锁屏歌词机制）：
     * 它们的标题经常是歌词行，歌曲信息要反过来从 ARTIST 里取。
     */
    val ARTIST_STRUCTURE_PACKAGES: Set<String> = setOf(
        "com.kugou.android",
        "com.kugou.android.lite",
        QQ_PACKAGE
    )

    /**
     * 需要「让宿主以为正连着无线音频输出」的播放器。
     *
     * 酷我、汽水这类播放器只在检测到蓝牙 / A2DP 时才会上报完整的媒体信息与歌词相关回调，
     * 否则 MediaSession 一直是空的。这里只把两个查询方法改成恒为 true，不连接任何真实设备。
     */
    val BLUETOOTH_BOOST_PACKAGES: Set<String> = setOf(KUWO_PACKAGE, LUNA_PACKAGE)

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
                LocalSource(BaseDir.CACHE, "lyric", LyricFormat.LRCX, listOf("lrcx", "lrc")),
                LocalSource(BaseDir.EXTERNAL_CACHE, "lyric", LyricFormat.LRCX, listOf("lrcx", "lrc"))
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
        // 酷我：歌词缓存（krc / lrc）散落在自己的私有目录，不同版本路径不一样，
        // 能命中哪个算哪个；都没有还会由 findInOwnStorage 扫一遍自己的目录。
        // 之前完全没有配方，酷我全程只能等网络嗅探，而它的歌词请求不走 okhttp，
        // 结果永远只推得出歌曲名和歌手（用户反馈）。
        LocalRecipe(
            packageName = KUWO_PACKAGE,
            displayName = "酷我音乐",
            sources = listOf(
                LocalSource(BaseDir.FILES, "lyric", LyricFormat.KRC, listOf("krc", "lrc")),
                LocalSource(BaseDir.FILES, "lyrics", LyricFormat.KRC, listOf("krc", "lrc")),
                LocalSource(BaseDir.CACHE, "lyric", LyricFormat.KRC, listOf("krc", "lrc")),
                LocalSource(BaseDir.CACHE, "lyrics", LyricFormat.KRC, listOf("krc", "lrc")),
                LocalSource(BaseDir.FILES, "krc", LyricFormat.KRC, listOf("krc", "lrc")),
                LocalSource(BaseDir.EXTERNAL_FILES, "kwmusic/lyric", LyricFormat.LRC, listOf("lrc", "krc")),
                LocalSource(BaseDir.EXTERNAL_FILES, "kwmusic/krc", LyricFormat.KRC, listOf("krc", "lrc"))
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
        ),
        // 汽水音乐不走「按扩展名扫目录」：它的歌词是按歌曲 id 命名的 JSON 缓存，
        // 由 LunaLyric 按 id 直接定位，这里留空配方只为在日志里显示平台名。
        LocalRecipe(
            packageName = LUNA_PACKAGE,
            displayName = "汽水音乐",
            sources = emptyList()
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
