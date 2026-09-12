# 星河 (XingHe)

> 星流（AstraFlow）的 LyricON 歌词提供者
> —— 离线读取播放器本地已有的歌词并提供给星流，由星流原生「胶囊歌词」呈现。

---

## 简介

星河 是 [LyricON](https://github.com/Proify/lyricon) 生态中的「歌词提供者」（Provider）模块，基于 **libxposed**（LSPosed 101+）实现。

它只做一件事：把当前播放歌曲的歌词按 LyricON 协议实时推送给星流，由星流原生「胶囊歌词」负责在流体云岛等位置呈现。

**完全离线**：不请求任何在线歌词接口，只读取播放器自己缓存的歌词文件；本地没有歌词就什么都不推送。

## 支持

| 应用 | 包名 | 本地歌词来源 |
| --- | --- | --- |
| MeloYou | `com.music` | `files/songLyric.json`（播放器自身导出） |
| 酷狗音乐 | `com.kugou.android` | `kugou/lyrics`、`kugou/temp_lyrics` 的 KRC |
| 酷狗概念版 | `com.kugou.android.lite` | 同上 |
| 波点音乐 | `cn.wenyu.bodian` | `cache/lyric` 的 LRCX |
| QQ 音乐 | `com.tencent.qqmusic` | `qqmusic/qrc` 的 QRC |
| 网易云音乐 | `com.netease.cloudmusic` | `LrcCache`（文件名与内容自带歌曲 id） |
| 欢太 / OPPO 音乐 | `com.heytap.music` | `lyric` 目录的 alm3ll |

歌词来自各 App 自身的本地缓存，因此**需要该 App 之前播放或加载过这首歌的歌词**（在 App 里能看到歌词即可）。

新增平台：在 `Constants.LOCAL_RECIPES` 里加一条配方（目录 + 格式），再把包名加进 `scope.list` 与 `arrays.xml`。

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

### 匹配策略

切歌后会在歌词缓存目录里查找当前歌曲，按下述证据打分，取分数最高的候选：

- 正文 `[ti:]`/`[ar:]` 与当前歌名、歌手一致（最可靠）
- 文件名或首行包含歌名（酷狗的「歌手 - 歌名-hash.krc」属于这一类）
- 文件名或内容里的歌曲 id 与系统给出的 `mediaId` 一致
- 文件写入时间接近切歌时刻
- 歌词总时长与歌曲时长吻合

播放器通常是「先切歌、后写歌词」，所以星河在切歌后的约 2 分钟内会按退避节奏反复重试，避免首次播放的歌曲漏词。

## 应用界面

模块内置一个简洁的设置页（`SettingsActivity`），与歌词推送共用同一份开关状态：

- **开关**：启用模块、隐藏桌面图标
- **快捷入口**：启动 LSPosed、查看支持平台
- **说明卡片**：支持的应用 / 歌词来源 / 匹配策略 / 关于星河 / 创作者 / 免费公益

界面上的「支持应用」列表与代码里的适配表始终一致，每次更新都会同步调整。

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
│   ├── ui/SettingsActivity.kt          # 模块设置 / 说明页
│   └── xposed/
│       ├── HookEntry.kt                # libxposed 入口与分派
│       ├── Constants.kt                # 常量与各平台本地歌词配方
│       ├── ModuleLogger.kt             # 日志
│       ├── XingHeLyricProvider.kt      # MeloYou 歌词提供者
│       ├── LocalLyricProvider.kt       # 通用本地歌词提供者
│       ├── LyricLocal.kt               # 本地缓存解析与匹配
│       └── Qrc.kt                      # QQ QRC 解密（3DES）
├── res/                                # 资源（含界面图标与背景）
└── resources/META-INF/xposed/          # 模块声明（scope / module.prop / java_init）
```

## 更新日志

### v1.3
- **界面重做**：设置页改为卡片式布局，新增支持平台、歌词来源、匹配策略等说明
- **歌词提供者修复**：多来源查找 + 多证据匹配 + 切歌后自动重试，解决只推歌名歌手、或完全不推送的问题
- 支持范围与界面文案随版本同步更新

### v1.1
- 移除在线歌词接口，改为完全读取本地缓存

## 致谢

- [LyricON](https://github.com/Proify/lyricon) —— 歌词协议与 Provider SDK
- [libxposed](https://github.com/libxposed) —— 现代 Xposed API
- [LyricProvider](https://github.com/tomakino/LyricProvider) —— 各平台本地歌词格式的解析思路参考

## 许可

本项目基于 MIT 开源，详见 [LICENSE](LICENSE)。

## 关于

免费公益 · 创作者 yiyi · QQ：2979208016
