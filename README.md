# AstraGalaxy

> 星流（AstraFlow）的 LyricON 歌词提供者
> 把当前播放歌曲的歌词按 LyricON 协议推给星流，由星流原生「胶囊歌词」呈现。

---

## 简介

AstraGalaxy 是 [LyricON](https://github.com/Proify/lyricon) 生态中的「歌词提供者」（Provider），基于 **libxposed**（LSPosed 101+）实现。

它只做一件事：把当前播放歌曲的歌词按 LyricON 协议实时推送给星流。自身没有播放界面、没有歌词渲染、没有悬浮窗，不修改播放器行为，是纯只读旁路。

## 功能

- **本地歌词优先**：读播放器自己缓存好的歌词文件，离线可用；
- **网络歌词旁路**：本地没有时，在播放器进程里只读嗅探它刚收到的网络歌词——洛雪音乐助手这类只在内存里显示歌词、不落盘的播放器也能用；
- **逐字歌词**：KRC / QRC / 网易云 yrc 的字级时间轴会一并推送；
- **翻译与音译**：网易云 tlyric / 音译字段随行推送；
- **歌词索引**：把「歌曲 id / 歌名 / 歌手 → 文件」记在播放器自己的缓存目录里，二次切歌直接定位，不再重扫目录；
- **元数据兜底**：两条通道都没拿到歌词时，推送「歌名 - 歌手 - 时长」；
- **作用域开放**：推荐作用域已列好常用平台，也可以在 LSPosed 里自行勾选任意音乐应用。

## 支持

| 应用 | 包名 | 歌词来源 |
| --- | --- | --- |
| MeloYou | `com.music` | `files/songLyric.json` |
| 酷狗音乐 | `com.kugou.android` | `kugou/lyrics`、`kugou/temp_lyrics` 的 KRC |
| 酷狗概念版 | `com.kugou.android.lite` | 同上 |
| 波点音乐 | `cn.wenyu.bodian` | `cache/lyric` 的 LRCX |
| QQ 音乐 | `com.tencent.qqmusic` | `qqmusic/qrc` 的 QRC |
| 网易云音乐 | `com.netease.cloudmusic` | `LrcCache`（文件名与内容自带歌曲 id） |
| 欢太 / OPPO 音乐 | `com.heytap.music` | `lyric` 目录的 alm3ll |
| 酷我音乐 | `cn.kuwo.player` | 无线输出伪装 + 网络歌词旁路 |
| 汽水音乐 | `com.luna.music` | `cache/NetCacheLoader` 里按歌曲 id 命名的歌词 JSON |
| 洛雪音乐助手 | `cn.toside.music.mobile` | JS 歌词桥（歌词模块 + 自定义音源回调） |

上面这些平台都不需要额外配置；没列出来的应用勾选作用域后同样会尝试网络 / 桥接嗅探。

## 实现方式

歌词一共三条来路，优先级从近到远：

1. **本地缓存文件**：按平台配方（目录 + 格式）读取，KRC、QRC、LRC、LRCX、alm3ll、网易云 yrc 与 JSON 包装都能解析；
2. **落盘索引**：解析成功的文件会记进播放器 `cacheDir` 下的索引，之后同名 / 同 id 的歌直接命中，不再扫目录；
3. **网络旁路嗅探**（通用，两条腿）：
   - **HTTP**：挂 `okhttp3.ResponseBody#string()/#bytes()` 的后置钩子，只读返回值；
   - **JS 桥**：React Native 播放器（洛雪音乐助手）的歌词只在 JS 内存里流转，HTTP 层也被改名，于是改在「Java → JS」的必经出口上只读返回值——歌词模块 `play/setLyric`、自定义音源回调、`PromiseImpl#resolve`、`CatalystInstanceImpl#callFunction`、`CxxCallbackImpl/CallbackImpl#invoke`、`ReactContext#emitDeviceEvent`。

所有旁路都只读：不拦截、不改写、不阻断，认不出歌词形状就什么都不做，对播放器性能与行为零影响。歌词先到、歌曲信息后到时，会先缓存再按歌名匹配后补推。

模块本体**不请求任何在线歌词接口**：歌词要么来自播放器自己缓存的文件，要么来自播放器自己刚收到的响应。

## 安装 & 使用

1. 在 LSPosed 中启用 AstraGalaxy，并勾选要适配的音乐 App（推荐作用域已默认勾选洛雪音乐助手等常用平台，也可以自行添加）；
2. 重启目标音乐 App；
3. 在星流里开启「胶囊歌词」。

如果只看到歌名和歌手、没有歌词，说明这首歌本地缓存和网络旁路都没拿到歌词——那是兜底信息，不是故障。

## 应用界面

设置页只有一个开关卡和一个关于卡：

- 启用模块、隐藏模块桌面图标；
- 「启动 LSPosed」跳到管理器，「适配应用」列出支持情况；
- 「检查更新」只在点击时访问一次 GitHub Release，模块本体仍不联网。

## 项目结构

```
app/src/main/
├── kotlin/io/github/proify/lyricon/xinghe/
│   ├── xposed/
│   │   ├── HookEntry.kt          模块入口与作用域过滤
│   │   ├── Constants.kt          平台配方、包名与常量
│   │   ├── LocalLyricProvider.kt 媒体会话监听、歌词投递与调度
│   │   ├── LyricLocal.kt         各平台歌词格式解析（KRC/QRC/LRC/yrc/JSON）
│   │   ├── LyricIndex.kt         歌词文件索引
│   │   ├── HttpLyricSniffer.kt   okhttp 响应体只读旁路
│   │   ├── RnLyricBridge.kt      React Native 歌词桥只读旁路
│   │   ├── ModuleLogger.kt       日志
│   │   └── Qrc.kt                QRC 解密
│   └── ui/SettingsActivity.kt    设置页
├── res/values/arrays.xml         推荐作用域
└── resources/META-INF/xposed/    模块声明与作用域
```

新增平台：在 `Constants.LOCAL_RECIPES` 里加一条配方（目录 + 格式），再把包名加进 `scope.list` 与 `arrays.xml`；如果它走网络或 JS 桥，则不需要配方。

## 致谢

- [LyricON](https://github.com/Proify/lyricon) —— 歌词协议与 Provider SDK
- [libxposed](https://github.com/libxposed) —— 现代 Xposed API
- [LyricProvider](https://github.com/tomakino/LyricProvider) —— 各平台本地歌词格式的解析思路参考

## 许可

MIT License。免费公益，禁止倒卖与付费代装。

## 关于

创作者：yiyi · QQ 2979208016
