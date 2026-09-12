# 星河 (XingHe)

> 星流（AstraFlow）的 LyricON 歌词提供者
> —— 离线读取播放器本地已有的歌词并提供给星流，由星流原生「胶囊歌词」呈现。

---

## 简介

星河 是 [LyricON](https://github.com/Proify/lyricon) 生态中的「歌词提供者」（Provider）模块，基于 **libxposed**（LSPosed 101+）实现。

它只做一件事：把当前播放歌曲的歌词按 LyricON 协议实时推送给星流，由星流原生「胶囊歌词」负责在流体云岛等位置呈现。星河自身**不含任何开关、入口或渲染逻辑**，装上即用。

**完全离线**：不请求任何在线歌词接口，只读取播放器自己缓存的歌词文件；本地没有歌词就什么都不推送。

## 支持

| 应用 | 包名 | 本地歌词来源 |
| --- | --- | --- |
| MeloYou | `com.music` | `files/songLyric.json`（播放器自身导出） |
| 酷狗音乐 | `com.kugou.android` | `files/kugou/lyrics/*.krc` |
| 酷狗概念版 | `com.kugou.android.lite` | `files/kugou/lyrics/*.krc` |
| 波点音乐 | `cn.wenyu.bodian` | `cache/lyric/*.lrcx` |
| QQ 音乐 | `com.tencent.qqmusic` | `files/qqmusic/qrc/*.qrc` |
| 网易云音乐 | `com.netease.cloudmusic` | `files/LrcCache/<歌曲 id>` |
| 欢太 / OPPO 音乐 | `com.heytap.music` | `files/lyric/*.alm3ll` |

歌词来自各 App 自身的本地缓存，因此**需要该 App 之前播放或加载过这首歌的歌词**（在 App 里能看到歌词即可）。

新增平台：在 `Constants.LOCAL_RECIPES` 里加一条配方（目录 + 格式），再把包名加进 `scope.list`。

## 原理

```
┌──────────────┐  本地歌词缓存   ┌────────────────┐  LyricON 协议   ┌──────────┐
│  音乐 App    │ ──────────────▶│ 星河            │ ───────────────▶│ 星流      │
│ (MediaSession)│  直接读取      │ (LocalLyric)   │   实时推送歌词   │ (胶囊歌词)│
└──────────────┘                └────────────────┘                 └──────────┘
```

- **入口**：`HookEntry`（`XposedModule`），按目标进程分派
- **MeloYou**：`XingHeLyricProvider` 读取播放器写入的歌词文件并按协议推送
- **其他平台**：`LocalLyricProvider` 监听 `MediaSession` 元数据 → `LocalLyricFinder` 在 App 自己的歌词缓存目录里匹配
- **解析**：`LyricLocal`（KRC / QRC / LRC / 网易云缓存），`Qrc.kt`（QQ QRC 的 3DES 解密）

匹配策略：正文自带 `[ti:]`/`[ar:]` 的按歌名+歌手精确匹配；没有歌名的（OPPO / 网易云）依次按 `mediaId`、最近写入时间、歌曲总时长兜底。

## 下载

前往 [Releases](https://github.com/2979208016/XingHe-AstraFlow-Provider/releases) 下载最新 APK 直接安装使用。

## 开源说明

本仓库开源的是**应用本体源码**——即 `app/src` 下的 Kotlin 代码、资源文件与 libxposed 模块声明，供学习与二次修改。

构建配置（Gradle 脚本）与第三方构建工具不属于本项目所有，故未包含在仓库中；如需自行编译，请自备构建环境。

## 安装 & 使用

1. 设备需已安装 **星流（AstraFlow）** 与 **LSPosed**
2. 安装本模块 APK，在 LSPosed 中**启用星河**并勾选需要歌词的音乐应用
3. 重启目标音乐应用
4. 在**星流**中开启「胶囊歌词」，播放音乐即可看到歌词上岛

## 项目结构

```
app/src/main/
├── kotlin/io/github/proify/lyricon/xinghe/
│   ├── ui/SettingsActivity.kt          # 模块说明页
│   └── xposed/
│       ├── HookEntry.kt                # libxposed 入口与分派
│       ├── Constants.kt                # 常量与各平台本地歌词配方
│       ├── ModuleLogger.kt             # 日志
│       ├── XingHeLyricProvider.kt      # MeloYou 歌词提供者
│       ├── LocalLyricProvider.kt       # 通用本地歌词提供者
│       ├── LyricLocal.kt               # 本地缓存解析与匹配
│       └── Qrc.kt                      # QQ QRC 解密（3DES）
├── res/                                # 资源
└── resources/META-INF/xposed/          # 模块声明（scope / module.prop / java_init）
```

## 致谢

- [LyricON](https://github.com/Proify/lyricon) —— 歌词协议与 Provider SDK
- [libxposed](https://github.com/libxposed) —— 现代 Xposed API
- [LyricProvider](https://github.com/tomakino/LyricProvider) —— 各平台本地歌词格式的解析思路参考

## 许可

本项目基于 MIT 开源，详见 [LICENSE](LICENSE)。

## 关于

免费公益 · 创作者 yiyi · QQ：2979208016
