package io.github.proify.lyricon.xinghe.xposed

/**
 * 星河模块常量。
 *
 * 星河的唯一定位：星流（AstraFlow）的 LyricON「歌词提供者」。
 * 它把各种播放源的歌词按 LyricON 协议推送给星流，由星流原生「胶囊歌词」
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

    // ---------------- 通用在线歌词提供者（MediaSession + QQ/网易云在线匹配） ----------------

    /**
     * 通用在线歌词提供者 hook 的音乐应用白名单。
     * 这些应用都会发布 MediaSession 元数据，统一走「元数据 -> 在线匹配歌词」通道；
     * 新增平台只需在此追加包名并加入 scope.list。
     */
    val CLOUD_PLAYER_PACKAGES: List<String> = listOf(
        "com.netease.cloudmusic",          // 网易云音乐
        "com.hihonor.cloudmusic",          // 网易云音乐（荣耀版）
        "com.tencent.qqmusic",             // QQ 音乐
        "com.tencent.qqmusicpad",          // QQ 音乐 HD
        "com.kugou.android",               // 酷狗音乐
        "com.kugou.android.lite",          // 酷狗概念版
        "com.kuwo.cn",                     // 酷我音乐
        "com.spotify.music",               // Spotify
        "com.apple.android.music",         // Apple Music
        "com.maxmpz.audioplayer",          // Poweramp
        "com.luna.music",                  // 汽水音乐
        "com.ikunshare.music.mobile",      // LX Music
        "com.lxnetease.music.mobile",      // LX Music（网易源）
        "com.miui.player",                 // 小米音乐
        "com.oplus.melody",                // OPPO 音乐
        "com.coloros.music",               // ColorOS 音乐
        "com.meizu.media.music",           // 魅族音乐
        "com.tencent.qqmusiccar"           // QQ 音乐车载版
    )

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
