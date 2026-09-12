# 星河 (XingHe)

> 星流（AstraFlow）的 **LyricON 歌词提供者** —— 把各主流音乐平台的歌词实时提供给星流，由星流原生「胶囊歌词」呈现。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Android-8.0%2B-green)
![API](https://img.shields.io/badge/libxposed-102-orange)

---

## 简介

**星河**是 [LyricON](https://github.com/Proify/lyricon) 生态中的「歌词提供者」（Provider）模块，基于 **libxposed**（LSPosed 102+）实现。

它只做一件事：**把当前正在播放的歌词，按 LyricON 协议实时推送给星流**，由星流原生「胶囊歌词」负责在流体云岛等位置呈现。星河自身**不含任何开关、入口或渲染逻辑**，装上即用。

## 能力

| 方式 | 说明 |
| --- | --- |
| **MeloYou 原生接入** | 直接读取 `com.music` 写入的 `songLyric.json` / `nowPlaying.json`，零延迟、逐字精准 |
| **通用在线匹配** | 对主流音乐平台，通过 MediaSession 元数据（标题/歌手/时长）在线匹配歌词 |

支持的音乐平台（通过在线匹配）：

> 网易云音乐、QQ 音乐、酷狗音乐、酷我音乐、Spotify、Apple Music、Poweramp、汽水音乐、波点音乐、LX Music、小米/OPPO/ColorOS/魅族 音乐等

新增平台只需把包名加入 `app/src/main/resources/META-INF/xposed/scope.list`。

## 原理

```
┌──────────────┐   MediaSession / 文件   ┌────────────┐   LyricON 协议   ┌──────────┐
│  音乐 App     │ ───────────────────────▶│   星河      │ ───────────────▶ │  星流     │
│ (com.music…) │   Hook 读取播放元数据      │(Provider)   │  实时推送歌词     │(胶囊歌词) │
└──────────────┘                          └────────────┘                   └──────────┘
```

- **入口**：`HookEntry`（`XposedModule`），按目标进程分派
- **MeloYou**：`XingHeLyricProvider` 读取播放器写入的歌词文件并按协议推送
- **通用平台**：`CloudLyricProvider` 监听 `MediaSession` 元数据 → 在线匹配歌词
- **QQ 音乐解密**：`QrcDecrypt` / `QqDESHelper` / `QqLyrics` 解密 QRC 歌词

## 下载

前往 [Releases](https://github.com/2979208016/XingHe-AstraFlow-Provider/releases) 下载最新 APK 直接安装使用。

## 开源说明

本仓库开源的是**应用本体源码**——即 `app/src` 下的 Kotlin 代码、资源文件与 libxposed 模块声明，供学习与二次修改。

构建配置（Gradle 脚本）与第三方构建工具不属于本项目所有，故未包含在仓库中；如需自行编译，请自备构建环境。

## 安装 & 使用

1. 设备需已安装 **星流（AstraFlow）** 与 **LSPosed**（支持 libxposed API 102）
2. 安装本模块 APK，在 LSPosed 中**启用星河**并勾选需要歌词的音乐应用
3. 重启目标音乐应用
4. 在**星流**中开启「胶囊歌词」，播放音乐即可看到歌词上岛

## 项目结构

```
app/src/main/
├── kotlin/io/github/proify/lyricon/xinghe/
│   ├── ui/SettingsActivity.kt          # 模块说明页
│   └── xposed/
│       ├── HookEntry.kt                # libxposed 入口
│       ├── Constants.kt                # 常量与平台白名单
│       ├── ModuleLogger.kt             # 日志
│       ├── XingHeLyricProvider.kt      # MeloYou 原生歌词提供者
│       ├── CloudLyricProvider.kt       # 通用在线歌词提供者
│       └── QqLyrics.kt                 # QQ 音乐歌词（含 QRC 3DES 解密）
├── res/                                # 资源
└── resources/META-INF/xposed/          # 模块声明（scope / module.prop / java_init）
```


## 致谢

- [LyricON](https://github.com/Proify/lyricon) —— 歌词协议与 Provider SDK
- [libxposed](https://github.com/libxposed) —— 现代 Xposed API

## 许可

本项目基于 **MIT** 开源，详见 [LICENSE](LICENSE)。

## 关于

**免费公益** · 创作者 **yiyi** · QQ：2979208016
