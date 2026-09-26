package io.github.proify.lyricon.xinghe

/**
 * 模块级常量：跟具体能力无关的、全插件共用的东西放这里。
 * 每个能力自己的常量放到各自的包里（例如 lyric/Constants.kt）。
 */
object Module {

    /** 本模块（宿主 APK）包名 */
    const val PACKAGE_NAME: String = "io.github.proify.lyricon.xinghe"

    /** 供 LyricON 生态识别的提供端标识 */
    const val LYRICON_PROVIDER_PACKAGE: String = PACKAGE_NAME

    /** 模块 Logo（SVG），给星流侧展示用 */
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
