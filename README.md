# AstraGalaxy

> 星流（AstraFlow）的 LyricON 歌词提供者，把当前播放歌曲的歌词按 LyricON 协议实时推送给星流，由星流原生「胶囊歌词」呈现。

## 功能

- **本地歌词优先**：读播放器自己缓存好的歌词文件，离线可用。
- **网络歌词旁路**：本地没有时，在播放器进程里只读嗅探它刚收到的网络歌词；歌词只在内存里显示的播放器也能用。
- **逐字歌词**：KRC / QRC / 网易云 yrc 的字级时间轴随行推送，翻译与音译字段一并推送。
- **歌词索引**：把「歌曲 id / 歌名 / 歌手 → 文件」记在播放器自己的缓存目录里，二次切歌直接命中。
- **元数据兜底**：两条通道都没拿到歌词时，推送「歌名 - 歌手 - 时长」。
- **纯只读旁路**：不拦截、不改写、不阻断，不修改播放器行为；模块本体不请求任何在线歌词接口。

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
| 酷我音乐 | `cn.kuwo.player` | 网络歌词旁路 |
| 汽水音乐 | `com.luna.music` | `cache/NetCacheLoader` 里按歌曲 id 命名的歌词 JSON |
| 洛雪音乐助手 | `cn.toside.music.mobile` | JS 歌词桥（歌词模块 + 自定义音源回调） |

上面这些平台开箱即用；没列出来的应用勾选作用域后同样会尝试网络 / 桥接嗅探。

## 使用

在 LSPosed 中启用 AstraGalaxy 并勾选要适配的音乐 App（推荐作用域已默认勾选常用平台），重启目标音乐 App，然后在星流里开启「胶囊歌词」。

只显示歌名和歌手、没有歌词时，说明这首歌本地缓存和网络旁路都没拿到歌词，那是兜底信息，不是故障。

## 许可

MIT License。免费公益，禁止倒卖与付费代装。

创作者：yiyi · QQ 2979208016
