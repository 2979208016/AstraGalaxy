# AstraGalaxy

> 星流（AstraFlow）增强插件。把手机里正在发生的事送到星河岛，并让你在岛上直接操作。
> 桌面上显示的名字是「星河」。

## 能力

### 歌词胶囊

把正在播放的歌词按 LyricON 协议实时推送给星流，由星流原生「胶囊歌词」呈现。

- **本地歌词优先**：读播放器自己缓存好的歌词文件，离线可用
- **网络歌词旁路**：本地没有时，在播放器进程里只读嗅探它刚收到的网络歌词
- **逐字歌词**：KRC / QRC / 网易云 yrc 的字级时间轴随行推送，翻译与音译字段一并推送
- **纯只读旁路**：不拦截、不改写、不阻断，不修改播放器行为

### 链接助手

在你复制或剪贴的文字里识别 http/https 链接，发现链接后**通过星河岛问你是否用浏览器打开**。

- 在 system_server 的剪贴板服务里挂一个钩子，用户在任意应用里复制文字都会触发
- 岛上卡片两个按钮「打开 / 忽略」，用户点了才跳浏览器，全程不用离开当前界面
- 打开方式可配：跟随系统默认 / 固定某个浏览器 / 每次让我选
- 只在本机匹配：不联网、不记录、不上传

## 作用域

| 能力 | 在 LSPosed 里勾什么 |
| --- | --- |
| 歌词胶囊 | 音乐播放器本身（MeloYou、酷狗、网易云等） |
| 链接助手 | **系统框架**（`system` / `android`，剪贴板服务住在这里） |

不要勾系统界面（`com.android.systemui`），链接助手不依赖它。

## 结构

```
app/src/main/kotlin/io/github/proify/lyricon/xinghe/
├── Module.kt                  全插件共用常量
├── xposed/HookEntry.kt        模块入口，按进程分派能力
├── lyric/                     歌词胶囊
│   ├── LocalLyricProvider.kt  通用提供者：本地缓存 + 网络旁路
│   ├── XingHeLyricProvider.kt MeloYou 专用提供者
│   ├── LyricLocal.kt          各平台歌词格式解析
│   ├── HttpLyricSniffer.kt    okhttp 响应体只读旁路
│   ├── RnLyricBridge.kt       React Native 歌词桥只读旁路
│   ├── KuwoLyric.kt           酷我歌词定位
│   ├── KuwoStreamTee.kt       酷我网络输出旁路
│   ├── Constants.kt           平台配方与包名
│   └── ModuleLogger.kt        日志
├── capability/link/           链接助手
│   ├── LinkPatterns.kt        链接识别（正则 + 文件名排除）
│   ├── LinkHook.kt            system_server 剪贴板钩子
│   ├── LinkHub.kt             岛上卡片枢纽（连接、投送、回调）
│   ├── LinkOpenActivity.kt    打开链接的中转页
│   └── BrowserChoice.kt       浏览器列表
├── settings/ModulePrefs.kt    全模块统一的偏好中心
├── app/                       应用进程（入口、链接接收）
└── ui/                        界面
    ├── widget/                毛玻璃卡片、自绘开关、动效
    └── page/                  首页、能力详情、底部抽屉
```

新增能力的做法：在 `capability/` 下建一个目录，在 `ModulePrefs` 加一个开关字段，在首页加一张能力卡。不需要动其它能力。

## 使用

1. 在 LSPosed 中启用 AstraGalaxy；
2. 勾选要适配的应用（推荐作用域已默认勾选常用音乐平台）；
3. 重启对应应用；
4. 打开星河 App，在首页按需开启能力（首页顶部会显示星河岛连接状态）。

## 许可

MIT License。免费公益，禁止倒卖与付费代装。
