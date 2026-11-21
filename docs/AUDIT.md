# 现状审计报告（2026-10-28，重构前基线）

## 1. 目录结构与分层违规
- 现结构 6 包：app/ lyric/ capability/link/ xposed/ settings/ ui/——**无 shared/ 与 hook/core 分层**；
- capability/link/ 混装了 system 侧 Hook（LinkHook）与模块进程逻辑（LinkHub）——进程边界违规；
- lyric/ 16 文件混装 Hook 类（4 provider）与纯解析（LyricLocal/Qrc/KuwoLyric/LunaLyric）与基建（ModuleLogger/SystemLog）。

## 2. Hook 注册散落点（13 处，无统一 Registry）
- LinkHook.install()（剪贴板）/ installFileWatcher()（downloads 进程）
- LocalLyricProvider：installRnBridge/hookApplicationLifecycle/hookMediaSession/installSniffer/installBluetoothBoost/installKugouLoader（6 处）
- XingHeLyricProvider / KuwoLyricProvider / RnLyricBridge / HttpLyricSniffer / KugouLyricLoader / SpotifyLyricProvider
- **注销**：全部无 unhook 机制（LSPosed 模块进程生命周期内可接受，但规范要求具备）。

## 3. 反射重复查找与未缓存点
- 39 处 Class.forName/getDeclaredMethod——LocalLyricProvider/XingHeLyricProvider 各 2 处 findClass 在回调内重复执行（低频可接受）；
- LinkHook 常量类名一次性查找 ✓；SpotifyLyricProvider MediaSession 方法在 install 时查 ✓（无回调内重复）。

## 4. 强引用泄漏风险
- LinkHub.appContext / SpotifyLyricProvider.appContext：**applicationContext**（非 Activity）——安全；
- Hook 类持有 XposedModule/logger——框架对象，随进程；
- 无 Activity/Service 强引用 ✓。

## 5. 运行时存储
- DevLog：内存环形缓冲 3000 条 + zip 常驻（节流 5s 刷新）——无日常文件 IO ✓；
- LyricIndex：cacheDir 4000 条上限 + mtime 淘汰 ✓；
- SpotifyLyricProvider 缓存：400 文件上限+最旧淘汰 ✓（本轮新增）；
- HookCrashLog：512KB 上限 ✓。

## 6. 跨子功能重复逻辑
- 正则解析 36 处（歌词 6 格式解析器各持一套行/词正则——合并价值低，保持）；
- su 并发读流 3 份（DevLog.suRead/LinkHub.suCat/LinkHook.rootCurrentSsid）——候选收拢 shared/（阶段C）。

## 7. 可能互相污染的静态状态
- LinkHub object 单例的 currentUrl/currentNumber/currentWifi/currentFile + lingerTask——单卡语义依赖持久层回退 ✓ 已修；
- SpotifyLyricProvider companion captured/pendingIds——跨实例共享但同进程，安全。

## 8. 死代码候选（分级）
- SAFE：0
- CAREFUL：9 项已删（hasLink/hasEmail/isZlib/isHintOnly/KEY_LINK_HINT_ONLY/showForBrowser/applyBlur/clipRounded/animateTrack）——DELETION_LOG 有台账
- RISKY（只报告）：Glass.kt 空壳 object（已转文档锚点保留）；ModuleLogger 宿主侧 devSink 旧机制注释残留（短路无害）。

## 9. 过期/误导注释清单（FIX 批次已完成）
- LinkHook/LinkHub 头部"终态架构/第4代"考古 → 已改现行行为；
- LinkPatterns 邮箱行 → 已同步；其余 KEEP 级（红线/坑点注释）保留。

## 10. 命名混乱点与脚手架残留
- 命名总体清晰（XxxProvider/XxxHook/XxxChoice 规范）；
- 脚手架残留：无空抽象层/无消费者接口；
- 巨型文件：LyricLocal(1381)/LocalLyricProvider(1308)/KuwoLyricProvider(759)/XingHeLyricProvider(624)/LinkHub(557+新增)——拆分需等行为稳定后按规范独立批次进行（风险高，本轮不动）。

## 目标架构（阶段B 方案，供后续轮次实施）
```
xinghe/
├── app/          # 模块自身进程（UI/设置/诊断）——现 ui/+app/ 合并
├── hook/         # 目标进程加载的 Hook
│   ├── core/     # HookRegistry/反射缓存/日志标签
│   ├── cimu/     # 歌词（4 provider+Spotify）
│   ├── link/     # 剪贴板链接/手机号
│   ├── wifi/
│   └── file/     # 下载检测
└── shared/       # 纯 Kotlin：LinkPatterns/解析器/su 读流
```
实施顺序：先 shared/ 抽纯函数 → hook/core 建 Registry → 逐子功能迁移 → 巨型文件拆解放最后。


## 补充（2026-10-28 05:40）：死代码批次的三起删失事故与最终处置

CAREFUL 批次（9 项）执行中发生三起"删除脚本吞邻居"事故，全部当场发现恢复：
1. Motion.kt：animateTrack 删除吞掉 pressFeedback/breathe/fadeInUp（全站 UI 在调）；
2. PickerSheet.kt：showForBrowser 删除留下残体括号（括号扫描抓获）；
3. LinkPatterns.kt：hasLink/hasEmail 删除吞掉 firstLink/firstPhone/hostOf 核心识别集
   （27 行壳自洽平衡——四件套查不出，靠"测试代码 grep 真实 API"暴露）。

### 新增红线（入核心记忆与 DELETION_LOG）
- 5.2 基线行数差审计：删后行数 vs 5.2 基线差值必须 ≈ 目标函数预期行数，超出=吞邻居；
- 删除后必查对外 API 的调用方引用（LinkHook 调 firstLink 而函数已亡=编译必挂）；
- 涉及删除的批次必须先写好测试用例（LinkPatternsTest 反向暴露了事故）。

### 巨型文件处置（RISKY 级——只报告不动）
按规范第 1 条"不确定默认不动"：LyricLocal(1381)/LocalLyricProvider(1308)/KuwoLyricProvider(759)/
XingHeLyricProvider(624)/LinkHub(约600) 的拆分挂 RISKY 待用户批准后独立批次执行——
歌词链路刚真机验证正常（"勇气"证据链），用户明确"不要大改现版本都能正常运行"。
函数超限 11 处已列 checks.py 待拆清单（60 行规则的目标清单，非立即强制）。

### 架构边界（目标轮#2 已执行——目录迁移，包名零改动）
迁移策略：Kotlin 允许目录≠包名——文件移入 hook/ 子目录而 package 声明原样，
import 零改动、行为零变化、checks.py 复验 0 错误。
- hook/cimu/：8 个歌词 Hook 类（LocalLyricProvider/XingHe/Kuwo/Spotify/RnLyricBridge/HttpLyricSniffer/KugouLyricLoader/PlayerTitle）
- hook/core/：HookRegistry/HookCrashLog/SystemLog/ModuleLogger
- hook/：LinkHook（system 侧智能上岛）
- 纯解析器（LyricLocal/Qrc/KuwoLyric/LunaLyric/LyricIndex/Constants）留 lyric/（shared 候选，属 RISKY 移动批）
- 模块进程逻辑（LinkHub/DevLog/LinkPatterns/BrowserChoice/InstallerChoice）留 capability/link/（app 进程候选）
后续：包名真正收敛到 hook.*/shared.*（需 import 全量改写）仍挂 RISKY 待批。

### shared/ 纯解析器迁移终判（目标轮#5）
- 入驻 shared/：Qrc.kt（QQ 加密解）、KuwoStreamTee.kt（流旁路）、SuReader.kt（su 读流）——零 Android 依赖 ✓
- 判定退回 lyric/：LyricLocal/KuwoLyric/LunaLyric/LyricIndex——均含 android.content.Context 或 android.util.Base64，
  不满足 shared 纯 Kotlin 红线（规范第 4 条），诚实分层保持 lyric/ 目录。


### HookRegistry 接入范围终判（目标轮#2）
- 已接入：handlePlayerPackage 的 4 个歌词 provider（installFeature 独立 try-catch/failureOf 留证）；
- 不接入：LinkHook（system 侧）——其 install/registerAllReceivers 是"跨挂载源幂等补挂"
  （boot/package-ready 双通道 + linkInstalledGate CAS），接 Registry 反而破坏幂等语义。
  这不是遗漏，是"不为 Registry 而 Registry"的架构判断。


## 补充（2026-10-28 07:30）：包名真正收敛执行完毕 + 编译修复记录

用户全批准后，"包名收敛到 hook.*/shared.*"从 RISKY 转为已执行。最终布局（package 与目录严格一致）：

| 目录 | package | 内容 |
| --- | --- | --- |
| `xinghe/` | `.xinghe` | Module.kt |
| `xinghe/app/` | `.app` | IslandService/IslandColdStartActivity/IslandDeliverReceiver/ReportProvider/XingHeApp |
| `xinghe/capability/link/` | `.capability.link` | LinkHub/LinkPatterns/DevLog/BrowserChoice/InstallerChoice |
| `xinghe/hook/` | `.hook` | LinkHook（system 侧） |
| `xinghe/hook/core/` | `.hook.core` | HookRegistry/HookCrashLog/ModuleLogger/SystemLog |
| `xinghe/hook/cimu/` | `.hook.cimu` | 8 个歌词 Hook 类 |
| `xinghe/lyric/` | `.lyric` | LyricLocal/LocalLyricFinder/KuwoLyric/LunaLyric/LyricIndex/Constants |
| `xinghe/shared/` | `.shared` | SuReader/Qrc/KuwoStreamTee |
| `xinghe/settings/` | `.settings` | ModulePrefs |
| `xinghe/ui/page/`、`xinghe/ui/widget/` | `.ui.page`、`.ui.widget` | 界面 |
| `xinghe/xposed/` | `.xposed` | HookEntry |

### 包名变更 ≠ 目录迁移：必须同时改三处
1. `package` 声明；
2. `import` 行；
3. **行内全限定引用**（如 `io.github...capability.link.LinkHook.KIND_FILE`）——这是本次 CI 反复报
   `Unresolved reference` 的根因，只改 1、2 时最容易漏掉。

### 本轮修掉的编译错误（全部来自包名收敛的余波）
- `LinkHook.kt:325` 行内全限定写成 `...capability.link.SuReader`（实际在 `shared`）→ 改正；
- `HookEntry.kt:140` 行内全限定写成 `...lyric.HookRegistry`（实际在 `hook.core`）→ 改用已 import 的简单名。
  连带消除 `HookEntry.kt:174` 的 `Cannot infer type for value parameter 'name'`（`failedNames` 的类型
  随 `HookRegistry` 一起解析失败）；
- `LinkHook.kt` `sysContext` 可变属性 smart cast 失败 → 先取局部 `val ctx = sysContext ?: return`；
- `hook/cimu/*` 缺 `import ...lyric.LocalLyric / LocalRecipe`（5 个文件）；
- `lyric/LyricLocal.kt` 缺 `import ...shared.QrcDecrypt`；
- `shared/KuwoStreamTee.kt` 缺 `import ...hook.cimu.looksLikeLyricPayloadHead`；
- 此前已修：`LinkPatterns` 残体、`LinkHook.rootCurrentSsid` 多余尾缀、`LinkHub.evictOldestForQuota`
  引用不存在的多卡队列、`LocalLyricFinder` 拆出后缺 `ConcurrentHashMap`/`abs`、
  `KuwoLyric.decodeDat` 被误删（已从 5.2 基线恢复）、`SpotifyLyricProvider` 的
  `declaredMethods + constructors` 歧义、`HookEntry` 的 `failureOf` 可空。

教训：`Unresolved reference` 报在"使用某符号"的行上，但根因常在**另一行的全限定前缀**。
按错误行号去改那一行的符号会改错对象——必须先确认该符号的真实声明包。

### 新增静态门：`docs/check_refs.py`（因环境不能本地编译）
四类检查，正是"改包名/拆文件"最容易漏的错误面：

| # | 检查 | 抓的典型错误 |
| --- | --- | --- |
| 1 | 跨包符号引用是否 import | 迁移后用了别的包的类却没 import |
| 2 | 行内全限定引用 `…xinghe.<pkg>.<Name>` 的包是否与 `Name` 真实声明一致 | `capability.link.SuReader`、`lyric.HookRegistry`——本轮 CI 报的正是这两处 |
| 3 | 项目内 `import` 是否指向真实存在的顶层声明 | 改包名时 import 整段改写漏了或写错 |
| 4 | 同包重复顶层声明 | 拆文件后最易出的 redeclaration |

正文匹配前剔除注释与**字符串字面量**——否则 KDoc 里提类名、广播 action 常量
（`"io.github…LAUNCH"`）、日志文案（`"IslandService started"`）全是误报。
`R`（aapt 生成）在 `SKIP_IMPORTS` 里排除。用注入式自测验证过第 2、3 项确实会报错。

用法：`python3 docs/check_refs.py`（0 问题退出码 0），与 `python3 docs/checks.py`
（60 行/大括号/壳文件门）配套使用。

### 文档同步
README 按实现修正：智能文件改挂 `DownloadProvider.update`、作用域表补「下载管理」行、
卡片模型改单卡替换、联网表述限定为 Spotify 歌词外发。

## 补充（2026-10-31）：5.3 功能轮（WiFi 凭据 / Spotify 取词 / 多宿主适配）

### WiFi 复制与分享（两个按钮都无反应）
真机日志只有 `wifi password read failed (no root or ssid not found)`，而同刻 root 已授权：
失败点在**SSID 取值**，不在权限。旧实现 `activeNetworkInfo.extraInfo` 在没有定位权限时给
`<unknown ssid>`，再退 `dumpsys wifi` 也常空，最后回落成字面量 `"WiFi"` 去查配置库 → 必然查不到。

- `LinkHook.currentWifiSsid()` 改为四路兜底：`cmd wifi status` → `dumpsys wifi`（`mWifiInfo SSID`）
  → `iw dev wlan0 link` → 配置库 `IsMostRecentlyConnected=true` 的块；`cleanSsid` 过滤
  `<unknown ssid>`/`0x`，`\bSSID:` 不吃 `BSSID:`。取不到就写占位符，**不再冒充网络名**。
- 复制/分享改由 system 侧执行（新增 `KIND_CLIP="clip"` / `KIND_TEXT_SHARE="text_share"`）：
  模块进程后台写剪贴板会被 appops 静默拦，非 Activity Context 起 `createChooser` 会被
  后台启动限制拦且异常被 `runCatching` 吞掉——这就是"点了完全没反应"的第二半根因。
- 密码字段兼容 `PreSharedKey` / `SAE-Password` / `WEPKeys` / `WEP?Keys`，`<null name="PreSharedKey">` 判开放网络；
  失败按「无 root」与「未找到该网络记录」分流提示。
- WiFi 状态回调搬离 system_server 主线程（2s 测速 + su 兜底原本会 ANR）。

### Spotify 词幕完全不出词
根因不是作用域：Spotify 9.1.92 把 okhttp 整体 R8 重打包，全部 dex 里只剩 4 个 `Lokhttp3/…`
描述符，**没有 `okhttp3.Headers` 类**——`hook okhttp3.Headers` 抓 authorization/client-token
的方案（本模块与词幕聚合版相同）在这版上永远抓不到头。匿名 token 路线亦已证伪
（`open.spotify.com/get_access_token` → `403 URL Blocked`）。

- `SpotifyLyricProvider` 改三级取词：本地缓存 → `OnlineLyricSource`（网易云公开接口 / LRCLIB）
  → 官方 color-lyrics（**仅当抓到头时**，属可选旁路）。抓头改为按"单数组参数"形态扫
  `p.k050`（本版本混淆名）与 `okhttp3.Headers`，失败只打 debug。
- trackId 严格校验 `spotify:track:<22 位 base62>`：媒体 id 常是 `track:淘汰|陳奕迅` 这类伪 id，
  直接拼 URL 会拿到 4xx；播客/广告前缀跳过查词。
- 每首歌先推空 lyrics 占位，签名（songId|行数|末行文本）去重；切歌用 generation 丢弃过期结果。

### 原文与翻译同时有效
- 新增 `lyric/online/`（网易云公开接口 + LRCLIB）：歌名归一化 + 时长 ±3s + 歌手校验，
  命中才出词；`LyricLines` 统一补 end/duration 并把译文贴到 `translation`、音译贴 `roma`。
  落盘缓存 `<cacheDir>/xinghe/lyric/online/`（正 7 天 / 负 30 分钟 / 400 文件上限）。
  未注入 Context 时经 `ActivityThread.currentApplication()` 延迟取宿主 Application（缓存目录必须落在宿主自己名下）。
- 实测结论（勿重复踩）：网易云公开接口 `/api/song/lyric` 与 `/api/song/lyric/v1` 参数全开
  **都不返回逐字 yrc**，只有 lrc + tlyric + romalrc。逐字只能来自本地 LrcCache。
- 歌词「翻译 0 行」不一定是链路 bug：网易云 LrcCache 的 `lrcTranslateLyric`/`yrcTranslateLyric`
  为空串时，`sanitize` 也不会凭空生成（它不碰 translation）。
- `LocalLyricFinder` 删掉「最近写入裸兜底」——那会把《心墙》的歌词推给《一张相片》。
  现在必须过三重约束：末行与时长差在容差内、文件写入落在本次播放窗口、无身份冲突；
  无身份证据时**只认唯一候选**，多候选放弃。

### 多宿主适配（对齐词幕聚合版 14 个 provider）
新增独立 provider：Apple Music(`com.apple.android.music`)、PowerAmp(`com.maxmpz.audioplayer`)、
QQ 音乐 HD(`com.tencent.qqmusicpad`)、Salt Player(`com.salt.music`)、MusicFree(`fun.upup.musicfree`)、
Gramophone(`org.akanework.gramophone`)、Symfonium(`app.symfonik.music.player`)，共用 `hook/cimu/LyricSupport.kt`
（内嵌标签读取 / 进度锚点 / 逐句累积）。`HookEntry.installExtended` 是唯一分派点，新增宿主只补一行 +
`Constants.EXTENDED_PLAYER_PACKAGES` 一项 + `arrays.xml` 作用域一项。

### 本轮编译/静态门教训
- `provider?.player.setXxx()` 是 **`RemotePlayer?` 上的非安全调用**——`player` 本身非空，
  但 `provider?` 已把整个表达式变可空，必须写成 `provider?.player?.setXxx()`。
  CI 在 Apple/QQMusicHD 各抓到一处。
- `LyriconFactory` 的 `player` 在 0.1.70 上是非空属性；聚合版反编译产物里看到的可空写法不可照搬。

### 文档同步（本轮）
README：Spotify 改为三级取词、联网表述扩到两个公开词源、WiFi 助手补 system 侧代做与 SSID 兜底、
作用域表列出全部 17 个歌词适配宿主、结构图补 `lyric/online/` 与 7 个新 provider；
`strings.xml` 的 `ui_apps_body` 同步为同一份清单。


## 5.4 首轮：LSPosed「推荐作用域」没有星标的根因

用户反馈「列表里有 Spotify，但没有『推荐作用域』那个标，模块也不会自动勾选」。核对交付 APK：
`AndroidManifest` 的 `xposedscope` 数组（`@array/xposed_scope`）里确实有 `com.spotify.music`，
但 APK 同时含有 `META-INF/xposed/java_init.list`（libxposed API 模块标志）。

LSPosed 侧逻辑（`ModuleUtil.InstalledModule`）：APK 里存在 `META-INF/xposed/java_init.list` 时视为
**modern module**，推荐作用域**只读 `META-INF/xposed/scope.list`**，不再读 manifest 的 `xposedscope`
数组；scope.list 缺失则推荐列表为空（`Collections.emptyList()`），于是既没有星标、也不会默认勾选。
我们的 `scope.list` 停留在 5.2 时代（11 个包，只有 `system` + 老平台），5.3 新增的 Spotify 等 8 个宿主
只写进了 arrays.xml —— 所以对 modern 模块形同不存在。

修法：`app/src/main/resources/META-INF/xposed/scope.list` 与 `arrays.xml` 的 `xposed_scope` 必须同步维护，
新增宿主时两处都加；README「作用域」小节已写明这条约束。

## 5.4 功能轮（Spotify 本地拦截 / 四源在线歌词 / 智能文件）

### Spotify：从「抓 okhttp 头」改为「截 protobuf 解析」
- 9.1.92 的 okhttp 被 R8 重打包（全 dex 只剩 4 个 `Lokhttp3/…` 描述符，没有 `okhttp3.Headers`），
  抓头方案必然失效。改成 hook `com.google.protobuf.h` 的两个静态解析入口
  （`m(h,byte[],int,c000)` 与 `parsePartialFrom(h,t5g,c000)`），所有 protobuf-lite 消息都收敛到这里。
- 响应类 `ColorLyricsResponse`(v2) / `LyricsWrapperResponse`(v3) 的字段名在 dex 里保留
  （`lines_` / `startTimeMs_` / `words_` / v3 `endTimeMs_` / v3 `translation_`），反射读取；
  v2 没有 end 字段，用下一行 start 补。
- 响应不含 trackId，靠 hook 请求仓库 `p.dce0`(v2)/`p.qhe0`(v3) 记录最近请求（FIFO 8 条、TTL 120s）做归属。
- 取词顺序改为：本地拦截（在途等待 ≤1.2s）→ 磁盘缓存 → 公开词源 → 官方接口（仍需 authorization）。
- 若某版本混淆名漂移：解析入口 hook 挂不上只记 debug 日志，行为回落到缓存在内的旧链路，不会更差。

### 在线词源：2 源 → 4 源
- 新增酷狗（`mobiles.kugou.com` 搜索 → `krcs.kugou.com` 候选 → `lyrics.kugou.com` 下载 base64 LRC）与
  QQ 音乐（`search_for_qq_cp` → `fcg_query_lyric_new.fcg`，`nobase64=1` 给明文）。
- 实测坑：`mobilecdn.kugou.com` 的 https 握手失败（只有 http 可用，targetSdk 36 禁明文）→ 换 https 镜像；
  `c.y.qq.com/soso/fcgi-bin/client_search_cp` 稳定返回 0 字节 → 换 `search_for_qq_cp`；
  酷我 `search.kuwo.cn/r.s` 返回单引号伪 JSON、`m.kuwo.cn/...songinfoandlrc` 大量 301 → 弃；
  咪咕/两个聚合源实测不可用 → 弃。
- 四源并行、总预算 6s、单源 2.5s、首个有效结果立即返回并写正缓存；负缓存只在「四源都答复且无传输失败」时写，
  `CACHE_VERSION` 升到 2（作废 v1 的「没词」结论）。
- 匹配质量：标题归一化（去括号/feat/破折号版本尾巴/修饰词）+ 歌手 token 包含 + 时长分档（>8s 淘汰）
  + 版本词/翻唱扣分 + 歌手优先池 + 兜底 30s 护栏。

### 智能文件：三条路径并存
- 主路径：system_server 旁听 `ActivityTaskManagerService`/`ActivityStarter` 的 startActivity* 系列，
  命中 APK 安装意图才出卡（只旁听、绝不拦截，`PROTECTIVE`）。
- 副路径：downloads provider 的 `DownloadProvider.update`（可选，需勾 `com.android.providers.downloads`），
  改成拼 `content://downloads/all_downloads/<id>`、只发内部广播给 system 侧统一去重出卡。
- 兜底：`MediaStore.Files.getContentUri("external")` 的 ContentObserver + 开机 20s 补扫最近 10 分钟的 .apk。
- 顺带修掉老 bug：`registerAllReceivers()` 依赖 `sysContext`，而旧代码只有剪贴板事件后才赋值，
  导致开机时 launch/下载/WiFi 三个接收器全部没注册。
- 编译坑：`DownloadManager.STATUS_SUCCESSFUL` 是 Int，`colLong()` 是 Long，CI 抓到
  `Operator '!=' cannot be applied to 'Long' and 'Int'`，统一 `.toLong()`。

## 补充（2026_10_31 晚）：5.5 轮（蓝牙岛 / 分功能收起时长）

### 蓝牙岛
- 触发面：只挂三个 profile 的 `CONNECTION_STATE_CHANGED`（A2DP / HEADSET / HEARING_AID），
  **不挂 `ACL_CONNECTED`**——否则手表、手环、车机这类非音频设备也会弹岛。
  同一次连接三个 profile 可能连发三条回调，用「设备名|MAC + 8s」去重。
- 卡内容：标题=设备名，副标题「已连接」，按钮 断开连接(PRIMARY) / 关闭蓝牙 / 设置 / 知道了。
- 断开连接：`BluetoothDevice` 没有公开的 disconnect，只能走 profile 代理
  （`adapter.getProfileProxy` + 反射 `disconnect(BluetoothDevice)`），A2DP(2) → HEADSET(1) → LE_AUDIO(21)
  逐个试并加 1500ms 闩；全失败退到系统蓝牙设置页，不静默失败。
- 关闭蓝牙：system 身份调 `BluetoothAdapter.disable()`（普通应用会被拦）。
- 胶囊图标自绘（96×96 ARGB，白色笔画），不依赖宿主资源目录，避免各 ROM 图标缺失。

### 显示时长：全局 + 分功能
- 新增 `linger_sec_<feature>` 偏好；未设置 = `LINGER_FOLLOW_DEFAULT(-1)` → 回落到 `card_linger_seconds`（默认 30s）。
- 出口统一为 `ModulePrefs.lingerSecondsFor(prefs, feature)`：`LinkHub.onCardShown`（岛内按钮触发的卡）
  与 `IslandService`（冷启动卡的终局回调）都改走它，`0` 一律表示常驻且**撤掉上一张卡残留的计时器**。
- 设置页：原来那张进度条改成「默认显示时长」，下面新增「各功能显示时长」卡，
  逐项列出 链接 / 手机号 / 智能文件 / WiFi / 蓝牙，可选「默认（跟随总设置）」或 0/10/20/30/60/120 秒。
- 分功能名与 `LinkHub.featureOf(kind)`、`IslandService.featureOf(kind)`、`LinkDetailActivity.LINGER_FEATURES`
  三处必须一致（都是 `link/phone/file/wifi/bt`）。

### 顺带重构（过 detekt 60 行上限）
- `LinkHub.cardFor` 拆成 `phoneCard/wifiCard/wifiCardLines/bluetoothCard/fileCard/linkCard`。
- `LinkHook.ensureLauncherReceiver` 拆出 `registerCompat` + 五个 `registerXxxReceiver`，
  顺带统一了 API 33+ 的 `RECEIVER_EXPORTED` 写法（原来五种接收器三种写法）。

### Spotify 命中率（5.5）
- 设备实测根因：磁盘缓存 13 条里 5 正 / 8 负（38%），而**同一时段在线层是 10 正 / 3 负（77%）**——
  差的这 5 首是「在线层本来有词，却被 12h 负缓存提前锁死」。所以先改负缓存语义：
  只有 `searchChecked()` 返回 `confirmedMissing`（所有源都明确答复「没这首词」）才写，超时/拒答不写；
  `NEGATIVE_CACHE_TTL_MS` 12h → 30min，`CACHE_VERSION` 升 2（旧条目自动失效）。
- 增酷我源：搜索 `search.kuwo.cn/r.s`（严格 JSON，`abslist[].MUSICRID`/`SONGNAME`/`ARTIST`/`DURATION`），
  歌词 `m.kuwo.cn/newh5/singles/songinfoandlrc`。酷我有周杰伦等华语全曲库，正好补网易云版权洞。
  坑：该接口**随机返回 `status:301`**（同 id 6 次实测 3 成功），故最多 3 次重试，仍失败调
  `OnlineHttp.markUnverified()` 而不写负缓存。外文歌返回双语混排，按「同时间戳成对」或
  「中文行落在下一句原文时间上（300ms 容差）」两种排布拆原文/译文。
- 查询变体：`MatchQuery.searchKeywords` + `QueryVariants`（原词 → 去括号 → 首位歌手 → 折叠重音…），
  命中即停、剩余预算 <700ms 不再换词。时长从「>8s 硬淘汰」改加减分（≤2s +30 / ≤5s +18 / ≤15s +6 / 其他 −12）。
- protobuf 拦截结果除当前 key 外再按 `id:<trackId>` 落一份，下次同曲直接命中。
- 离线回测：42 首中英混合（含 feat./Remaster/Live）v1 与 v2 都 30/30 覆盖；逐源「有歌手正确候选」
  netease 38/42、qq 42/42、kugou 42/42、kuwo 42/42 —— 加源提升的是冗余度，不是靠放宽阈值灌水。
- 遗留：酷我抖动约 85% 成功；翻唱 precision（网易云只有翻唱时可能先出翻唱词）；繁简未做映射；
  `Time (Clock of the Heart)` 这类带实义括号的名字仍会被剥括号。

### 智能文件：完整性闸门 + 幽灵卡（5.5）
- 新增 `hook/ApkIntegrityGuard.kt`（`verify(uri, expectedSize)` 四层校验，
  常量 `MIN_APK_BYTES=1024`、`EOCD_MIN_LEN=22`、`EOCD_SCAN_BYTES=65557`）。
  关键判定：读该 fd 的 channel，头 4 字节 `PK\x03\x04`；尾部倒数 65557 字节内找 EOCD `PK\x05\x06`，
  **要求 `i + 22 + commentLen == tail.size`**（注释长度正好收在 EOF），截断/追加写/拼接包全挡掉。
- `ApkInstallWatcher.offer()` 顺序：enabled → 名字闸门 → 去重 → 锁内二次去重 → 完整性 → 新鲜度
  → **最后才写去重表**（先写表会把随后真正的完成事件挤掉 10 分钟）。
- 幽灵卡的根因是拿数据库 `last_modified` 当新鲜度（厂商 ROM 会重写）；改用 `Os.fstat` 的 st_mtime：
  下载 10 分钟、媒体库 60 秒（旧实现 10 分钟 + 开机补扫）、安装意图不限。
- 跨进程信号带 `EXTRA_FILE_DOWNLOAD_ID` 与 `EXTRA_FILE_SENT_AT`（elapsedRealtime），接收方 >30s 丢弃，
  且**不采信广播里的名字/URI**，一律按 id 重查下载库。
- 分享修好：`EXTRA_STREAM` 的读授权同时写进 `clipData`（部分 ROM 不做迁移时接收方读不到）。
- 出卡前 `openFileDescriptor(uri, "r")` 探活，打不开宁可不弹。

### 蓝牙岛断开无效 + 单功能时长行内展开（5.6）
- 断开无效的真根因：`launchBtDisconnect` 在 **system_server 主线程**上执行，却用
  `CountDownLatch.await(1500ms)` 等 `BluetoothProfile.ServiceListener#onServiceConnected`
  —— 该回调正是投递到同一个主线程，于是必然等满超时、`requested=false`，再退去设置页，
  表现为「点了没反应」，同时白卡 system_server 1.5 秒（ANR 隐患）。
  改法：整条链路纯异步——`getProfileProxy` → 回调里判 `connectedDevices` 含该设备 →
  反射 `disconnect(BluetoothDevice)` → `closeProfileProxy(profileId, proxy)`（必须传真 proxy，
  不能传 null），不接受就链到下一个 profile（A2DP → HEADSET → LE Audio(22)），全失败才开设置页。
- 时长设置从「独立卡片 + 档位选择」改成「子功能行内展开」：在 `switchRow()` 里加可选 `feature`
  参数，行尾加箭头，面板作为**兄弟 View** 插在该行下方（`LinearLayout` 垂直流式布局，展开后
  下方内容自动下移，不会遮挡）；面板内是与默认卡同一套 `SeekBar(0..120)`，拖动即写
  `linger_sec_<feature>`，未单独设置时显示「默认（跟随总设置）（30 秒）」并可一键「恢复默认」。
- 教训：任何在 `system_server` 里跑的 Xposed hook，都不能在主线程上用闩等待回调；
  回调线程与调用线程重叠时闩一定会超时。

### Spotify 歌词与歌曲错配（5.6）
- 现象：反复拖动进度条时偶发「词是另一首歌的」；错一次后同一首会一直错（错词被写进磁盘缓存）。
- 先穷举身份证据，确认不存在：v2/v3 四条响应消息（`ColorLyricsResponse`、`LyricsResponse`、
  `v3.LyricsWrapperResponse`、`v3.LyricsV3Response`）**没有**任何 22 位 base62 / `track_id_` /
  `uri_` 字段（`providerLyricsId_` 是歌词提供方文档 id，`syncLyricsUri_` 是资源地址）。
  trackId 只出现在请求 URL：`p.p5e0`（v2 `color-lyrics/v2/track/{trackId}` 与 `.../download/track/`）、
  `p.nhe0`（v3）。实现 `implements cce0` 的只有 `p.dce0`(v2 仓库) 与 `p.qhe0`(v3 仓库)，各持一族。
  所以只能靠「请求 → 响应」配对，关键是把配对做严。
- 旧实现的错位根因：单一 `ArrayDeque<Pending>` + `REQUEST_TTL_MS=120s`，且响应解析完才从队头取一条。
  Spotify 会为队列/预取/离线下载请求多首歌的歌词，上一首没等到响应的请求留在队里，
  下一个响应就被认领给错误曲目；`takeReady`/`awaitLyrics` 还接受 `UNKNOWN("")`，
  于是错词被写进缓存并长期复用。
- 改法（四层）：① `pendingV2`/`pendingV3` 分族队列，v2 响应只认领 v2 请求；② 切歌 `onTrackSwitch()`
  清空两条队列并清掉其它曲目的已抓结果；③ `PENDING_TTL_MS` 120s → **20s**，超时残留直接作废；
  ④ `capture()` 每条响应先消费一条请求，再要求 `request == 当前曲目` 且 `fitsDuration()`
  （末行 begin ∈ `[duration*0.35, duration+5s]`）才接受、才回调、才写缓存；缓存版本升 v3，
  旧的错词条目在 `readCache` 里校验不相容即删除。
- 教训：没有身份字段的异步响应，配对规则不能「取最近一次」；要么分族 + 消费式配对，
  要么宁可不接受——错词比没词更糟，而且会被缓存长期固化。

### 智能文件在 media 路径上全军覆没（5.7）
- 现象：下载完的 APK 完全不弹岛；日志里成片 `智能文件未就绪(media/NOT_OPENABLE)：xxx.apk`，
  连我们自己放进 `/sdcard` 的安装包也一样。
- 根因（root 实测，不是猜）：`system_server`（uid 1000）**能查 MediaStore 并拿到 `_data` 路径**，
  但**读不了 `/storage/emulated/0`**（`su 1000 -c 'head -c 4 <path>'` → Permission denied），
  也打不开 media uri（MediaProvider 侧拒绝）。而 `deliverFileCard` 出卡前还要
  `openFileDescriptor` 复检一次 —— 每一张卡都在最后一步被自己否掉。
  另一层：用户那次下载没经过 DownloadManager（其他 App 直接写 `/storage`），
  `onDownloadUpdate` 根本没有事件，只剩 media 这条必然失败的路径。
- 改法（分工重排）：system 只做「发现」——把真实路径用**显式广播 + 一次性口令**交给模块进程
  （`ApkBridge`/`ApkDiscoveryReceiver`）；模块进程（有 KernelSU root）`su stat` 拿权威大小/mtime →
  `su cp` 到 `filesDir/apk-incoming/`（不用 cacheDir：副本被系统清掉后卡就点不动了）→ 大小比对 →
  四层完整性校验 → 回一次 `file_ready` 请 system 出卡。system 侧校验「口令未过期 + uri 归属本方 Provider」
  后才出卡；安装/分享仍由 system 代发（BAL 豁免），uri 指向模块的非导出 Provider（`grantUriPermissions`）。
  「删除」改在模块进程 `su rm` 原包 + 清副本：system 删不掉 `/storage`，而副本只是中间产物。
- 教训：跨 uid 的能力边界要先量出来再设计。`system_server` 不是「什么都能读」——Storage 的
  FUSE 视图对 uid 1000 同样是拒的；把「读文件」放在 system 侧，整条链路注定在最后一步失败。

### 蓝牙断开把 A2DP 优先级写坏，媒体音频永久留在扬声器（5.8）
- 现象：卡片上「断开连接」之后，再连耳机，蓝牙显示已连接，但媒体音频一直从手机扬声器出，
  关掉蓝牙再开、重新配对都无效。
- 根因：5.7 想压住「断开 11 秒后设备自己重连」，在 disconnect 之前反射调了
  `BluetoothProfile#setPriority(device, 0)`（`PRIORITY_OFF`）。日志实证 `bluetooth priorityOff=true profile=2`
  ——A2DP 被成功写成了 0。但 `PRIORITY_OFF` 在 Android 里是「**用户禁用该 profile**」的持久语义，
  不只是「这次别连」：之后设备重连只会连上 HFP，A2DP 音频通道不再建立，媒体音频自然留在扬声器。
  这是 5.7 那次修「断开无效」引入的回归，比原问题严重。
- 改法：断开流程**绝不触碰优先级**（删掉 `setPriorityOff`）；改为在设备连上时做一次自愈：
  `restoreAudioRouting()` → A2DP `setPriority(device, PRIORITY_AUTO_CONNECT=1000)`，
  若此时 A2DP 尚未连上则反射 `connect(device)` 把音频通道拉起来，之后正常 `closeProfileProxy`。
  对没被写坏过的设备这次调用等价于「保持自动连接」，无副作用。
- 教训：隐藏 API 的「优先级」不是开关，是持久化的用户意图。想阻止重连，只能断开，不能替用户改配置。

### 智能文件副本由 root 创建后本进程读不了（5.8）
- 现象：5.7 装机后下载 APK 仍不弹岛；App 侧日志（报告 02_module_log.txt）给出确切一行：
  `智能文件副本未通过完整性校验：9815a477-fresh-test.apk NOT_OPENABLE`。
- 根因：`su cp` 以 root 身份创建副本，属主 `root:root` 权限 `rwxr-x---`；
  模块进程（uid 10454）连 `openFileDescriptor` 都打不开，校验第一步就返回 `NOT_OPENABLE`。
  root 实测：root 建的文件 `cat` → Permission denied；`chown 10454:10454` 后 `head -c 4` → `PK\x03\x04`。
- 改法：`copyFromRoot` 的 su 命令链加 `chown <myUid> <copy> && chmod 600 <copy>`（chown 用
  `Process.myUid()`，不写死 uid）；校验失败时顺手删掉不可用副本，避免留下垃圾。
- 教训：跨 uid 复制文件不是「复制完就完事」，属主/权限是链路的一部分；`su cp` 的产物默认不属于你。

### 智能文件「下载完成后不弹岛」（5.9）

**现象**：浏览器（不走 DownloadManager，直接写 /storage）下载完安装包，岛不弹；模块日志里只有
一次 `智能文件候选命中(media)`，App 侧 `智能文件副本未通过完整性校验：… TRUNCATED`。

**根因（三个叠加）**：
1. 浏览器是「先建文件、再流式追加」，媒体库第一次事件到达时文件只有半截 → `ApkIntegrityGuard`
   的 EOCD 收尾判定必然 `TRUNCATED`；
2. `ApkInstallWatcher.offer()` **先写去重表**（键 `n:<文件名>`，窗口 10 分钟），于是后面真正的
   「写完」事件全被 `智能文件去重(media)` 挡掉；
3. 媒体库路径的新鲜度窗口只有 60 秒，大文件从建文件到写完往往超过它，写完事件到了也会被
   `智能文件不新鲜` 丢掉。

**改法**：
- 去重键换成「真实路径」+ 指纹「大小|mtime」：文件又长了就算新候选，允许重新投递；
  抑制窗口降到 60 秒。
- 实时媒体库事件的新鲜度放宽到 30 分钟；开机补扫仍用 90 秒窄窗口（幽灵卡的唯一来源）。
- 新增周期性兜底扫描（每 3 分钟扫最近 10 分钟的 APK，最多 5 条）。
- App 侧 `SmartApkIntake` 先 `su stat` 等到「连续两次同样大小」（最多 5 秒），再复制；
  结构性拒绝（TRUNCATED/SIZE_MISMATCH/EMPTY/TOO_SMALL）时再复制一轮并重取大小。
- 系统侧 `LinkHook` 增加**有界重投**（6s/12s/25s/45s/90s/180s 六次），App 回执出卡即停；
  同一条路径不会重复排队。
- App 侧记录「已出过卡的指纹」，同一文件 10 分钟内不再出第二张卡。
- `BUILD_TAG` 改为取 `Constants.MODULE_VERSION`（原来手写 `link-hook 5.7`，导致 5.8 的日志
  仍打印 5.7，排查时误判「旧 dex 还在跑」）；`docs/checks.py` 新增该校验：常量必须等于
  `version.properties` 的 versionName。

**卡片与按钮（用户要求）**：正文写 `包名 / 下载于 / 大小`；按钮顺序 分享 → 查看 → 安装 → 知道了
（「知道了」在最后，「查看」用用户在设置里选定的文件管理器，空值=跟随系统）。

### 智能文件收尾：安装/查看给原始文件、副本可清理、重投不再放大（5.10）

真机反馈三条：

1. **点「安装」调起来的是 InstallX 的界面，还红字「签名验证」**。原因：我们给的是
   `content://<我方>.apkprovider/apk-incoming/<副本>`，InstallX 读不到真实路径，
   拿不到签名/版本信息就报校验失败。改法：安装与查看都优先给**原始文件**的 `file://`
   （安装器、文件管理器自己有权读 /storage），拿不到原始路径才退回我方 `content://`。
   顺带解决「查看打开后定位到我方私有目录、用户找不到原文件」。
2. **删掉「默认文件管理器」设置**：查看走系统选择器让用户自己挑就够，多一个需要维护的
   偏好只会多一条出错路径（用户明确要求删）。
3. **时隔三分钟又弹一张「下载完成」**：`sweepRecent()` 每 3 分钟扫最近 10 分钟的 APK，
   而「移动/重命名」会刷新 media 的 mtime，于是一个早就下好的包被当成新下载。改法：
   停用周期补扫（只保留启动时一次窄窗口补扫），兜底交给「下载事件 + 有界重投」。
4. **重投风暴**：`deliverToApp()` 每投递一次就 `scheduleApkRetry(attempt + 1)`，而同一候选
   会从 media/intent/重投三条路重复进来，日志里出现同一秒多次「第 N 次重投」。改法：
   同一路径同时只保留一个待执行重投（`apkRetryPaths` 真正当闸门用），次数用尽打
   「放弃重投」；App 侧 stat 失败（文件已删）时发 `file_abort` 信标，system 立即停投。
5. **副本堆积**：`deleteOriginal` 现在连副本一起删；`ApkIncomingStore.prune()` 在每次落地
   前清理 3 天前的副本、最多保留 8 个。
6. 自己发起的安装/查看意图补 `EXTRA_SELF_INSTALL`，安装意图拦截直接跳过，不再出现
   「点安装 → 又冒一张卡」。

### 智能文件「定时幽灵卡」+ 安装 EXDEV（5.11）

真机反馈三连：① 早就下好的包过了几分钟又弹一次；② 没有手动清理入口、清理周期不可选；
③ 点「安装」报 `INSTALL_FAILED_CONTAINER_ERROR … res=-18`，同一次失败还会让后续安装的应用损坏。

根因与改法：

1. **安装给原始路径 = EXDEV**：5.10 为了让 InstallX 能读到签名，把 `file://` 指向
   `/storage/emulated/0/...`；安装器抽取 native lib 时跨设备（res=-18 = EXDEV）复制失败，
   装出来的应用自然缺库。**安装改回数据分区副本的 `content://`**（`/data` 同分区，fd 可读）；
   「查看」仍是 `file://` 原始路径（文件管理器要定位真实目录）。
2. **幽灵卡三条来源**：媒体库对「移动/重命名」也会发变更；补扫按 mtime 判断；重投定时器
   在出卡后只是从集合里移除、**没有 cancel**，到点照样再投一次。
   - App 侧新增 `CARD_MAX_AGE_MS = 15min`：mtime 超过窗口直接判「不是新下载」并发
     `file_abort`，system 立即停投；
   - system 侧新增 `apkDeliveredAt`（path → 出卡时刻）30 分钟静默期，重复命中直接忽略；
   - `apkRetryFutures` 保存 `ScheduledFuture`，出卡/放弃时真的 `cancel(false)`。
3. **清理入口**：`ApkIncomingStore.clearAll()`（手动清全部副本 + 映射）、`usage()`（份数/大小），
   设置页「智能文件」下方两行：`清理暂存副本`（显示占用，点击即清）与`自动清理`
   （预设档位 不清理 / 1 / 3 / 7 / 30 天，`KEY_APK_CLEAN_DAYS`，默认 3 天），`prune()` 按它执行。

### 幽灵卡最后一层：按内容指纹去重（5.12）

5.11 的 mtime 闸门能挡「移动旧包」，但用户如果是**复制**一份旧包（mtime 变成新的），
或者同名包反复落盘，仍然会再弹。改成按内容指纹（大小 + 首尾各 256KB 的 MD5）在
`ApkIncomingStore` 里记 24 小时：同一份内容出过卡就不再弹，路径与 mtime 变化都不影响。

## 补充（2026-11-01）：6.0 界面重构轮

### 权限中心「LSPosed 模块加载」永远误报
- 旧实现读 `filesDir/xinghe_dev.log` 找 `IslandService started`。但 `capability/link/DevLog`
  是内存环形缓冲、不落盘，该串只可能出自 hook 侧的 `ModuleLogger`，而那里写的文件名/时机
  都对不上 → 按构造不可能命中。
- 第一版修法（`tail -c 200000` 最新日志再找 `link-hook <ver> loaded`）被真机证伪：日志已
  248KB，而证据行在文件第 24 行（约 3KB 处），tail 窗口必然漏。改为**服务端 grep 全文**
  （只回传命中行），对日志增长/轮转免疫。
- 教训：`su cat/tail` 的窗口大小是隐式假设，日志会增长，别把「最新」当「开头」。

### 「智能上岛」总开关会连带关掉「歌词胶囊」
- `LinkDetailActivity` 用反射扫所有 `KEY_*`，凡值以 `cap_` 开头就一起写；`cap_lyric_enabled`
  也是 `cap_` 前缀 → 关智能上岛会把歌词胶囊一起关掉。改为显式 5 项白名单
  （link / phone / file_installer / wifi / bluetooth）。
- 教训：靠命名前缀做「批量联动」迟早出事，联动关系要显式管理。

### 未用的旧资源与固定主题材质
- `SheetActivity` 的 `bg_glass_card` 固定 22dp 圆角 + 固定色，会盖掉 `glassCard()` 按 tokens
  生成的材质（莫奈圆角、液态玻璃描边全失效）→ 删除该 drawable，容器只走 tokens。
- `GlassSwitch` 硬编码 `R.color.*`，切预设后开关轨道仍是旧蓝 → 改读 `ThemeStore.current()`。

### QQ / TIM 聊天安装包检测
- 真机实测 QQ 落盘名有三种：`x.apk`、`x.apk.1`、**`星流.apk (25).1`（撞名时把 ` (序号)`
  插在扩展名与 `.1` 之间）**，还有 `.APK` 大写。只认 `.apk.1` 会漏掉最常见形态。
- 实现：不 hook QQ（用户明确禁止），只从 `/storage/emulated/0/Android/data/<qq|tim>/Tencent/QQfile_recv`
  读取，一次 `su` 扫三个目录；15 分钟新鲜度 + 双次 stat 等大小 + `size|mtime` 指纹去重；
  App 侧出卡（第三方文件没有 system 下发的 token，走广播必被丢弃）。
- 教训：文件名规则不要凭文档猜，`su ls` 看一眼真机数据比什么都快。

### Spotify 歌词与进度错位
- 缓/拖动期间 ticker 仍按墙钟外推；`speed` 上报 0 时被兜成 1.0f；每次上报无条件覆盖锚点，
  旧快照能把进度往回拽 3.5 秒；暂停切换后不建立锚点。→ 拆出 `anchorAdvancing`，用
  `elapsedRealtime` 外推，旧/滞后快照不采信，`|drift| > 2s` 视为 seek 直接重置。

### 界面重构到 Compose + 上游部件层（6.1）

6.0 的三栏是 View 自绘的：底栏是 `GradientDrawable` 画的圆角矩形，选中项是另一块更大的圆角矩形，
所以真机上是「方片、有明显边界」；页面没有任何背景取样与模糊，风格完全是自创的。参考项目
（ChizuruMizuhara）的观感并不来自它的配色，而来自它用的上游部件：`zone.ien.hig` 的 Cupertino
组件与 `io.github.kyant0:backdrop` 的 `LayerBackdrop`。因此 6.1 直接把整套 UI 迁到 Compose，
并把这些部件按上游原样移植进来（`ui/cupertino/`，`docs/checks.py` 对这份移植件只做最小检查，
不按本项目的「函数 ≤60 行」去拆，保持上游形状以便日后跟随上游更新）。

配套改动：

- 根构建加 `compose-compiler-gradle-plugin`，Kotlin 2.4.0 → 2.4.21；`app` 开
  `buildFeatures { compose = true }`，`minSdk 27 → 29`（hig 上游要求）。
- 依赖 `hig`/`hig-icons-extended`/`backdrop`/`shapes`/`capsule` + compose-bom。首次 CI 失败是
  `io.github.kyant0:shapes-android:1.2.1` 在 aliyun 镜像上 502，Gradle 因此把整个仓库标成不可用；
  把 `mavenCentral()` 提到 aliyun 之前后恢复正常。
- 三页：`ui/shell/{AstraApp,CapabilityPage,SettingsPage,AboutPage,PermissionCenterPage,DevPage}.kt`。
  底栏是上游 `AstraTabBar`（按住可拖动换页），整页画面经 `Modifier.layerBackdrop` 录进
  `LocalAstraBackdrop`，底栏据此做模糊与折射 —— 这才是「液态玻璃」的实际来源。
- 两处踩坑：① `ui/theme` 里旧的 View 版 `enum class StylePreset` 与新预设重名，改名为 `UiPreset`；
  ② `CapabilityPage`/`SettingsPage` 一开始都超过 60 行，按 checks.py 的规则拆成「每段一个小函数」。

### 权限中心「未检测到 LSPosed」是读了一个从不落盘的日志（6.1）

旧 `moduleLoaded()` 读 `filesDir/xinghe_dev.log` 找 `IslandService started`，而 `DevLog` 是内存环形
缓冲，从不写这个文件 —— 这段判断按构造永远返回 false，所以模块明明在跑也显示未检测到。
`shared/LspLoadProbe` 改成用 root 读 `/data/adb/lspd/log/modules_*.log`（最新 3 个文件），
用 `grep` 找 `link-hook <版本> loaded` 一类证据行。注意不能用 `tail -c 200000`：实测日志已 248KB，
而证据行在第 24 行附近，tail 会把唯一证据截掉，造成假阴性。


## 补充（2026-11-01）：6.2 界面收敛与体积优化

- **能力页点击无反应（严重）**：`CapabilityState` 的字段原来是普通 `var`，写入后 Compose 不会重组，开关与链接看着有涟漪但界面不动。改为 `mutableStateOf` / `mutableStateMapOf` / `mutableIntStateOf`。
- **层级改三级**：能力页只留「歌词胶囊」「智能上岛」两个主能力；智能上岛进二级页（总开关、默认显示时长、五个子功能入口、打开方式）；子功能各自三级页（启用、单独时长、智能文件的暂存清理）。
- **风格只留一套**：删除外观选择（澎湃/莫奈预设），统一液态玻璃；`AstraApp` 固定 `LocalEnableBlur = true`、底栏悬浮 36dp。
- 去掉设置页与开发者页重复的「详细日志」开关；开发者页进入即打包日志、按钮为「分享日志包」（移除「重新打包」）。
- **体积**：release 开 R8（`isMinifyEnabled` + `isShrinkResources`）并配 `app/proguard-rules.pro` 保留 LSPosed 入口类；APK 从 31.6MB 降到 2.95MB。
- 构建坑：`astraValue/astraSection` 的接收者是 `LazySectionScope`（`zone.ien.hig.section`），`AstraTabPage` 内容 lambda 的接收者是 `LazyListScope`，两者混用会报 receiver type mismatch；`ui/cupertino/**` 是上游移植件，`docs/checks.py` 对其豁免函数长度检查。

## 补充（2026-11-02）：7.7 审核整改与「按钮全失效」根因

5.12 被驳回的安全条款，7.3 已改了一轮（接收器加来源校验、日志加脱敏、文案补说明），
7.5 又用「root 落盘口令」补了一轮。7.7 复查后发现**两处真正的结构性错误**，
两处都不是「漏改文案」而是设计选错，这次一并推翻重做。

### 根因一：把 `getSentFromPackage()` 当门禁，自家广播也被丢掉（按钮全失效）

7.5 的链路是：卡片按钮 → `LinkHub.sendLaunch()` 发 `ACTION_LAUNCH` 广播 →
`system_server` 里动态注册的 `launchReceiver` 收下 → 代发 `startActivity` / 蓝牙断开 /
写剪贴板。门禁写成：

```kotlin
val tokenOk = TrustToken.matchesDirect(intent)          // 读 /data/local/tmp/.af_island_token
if (!tokenOk) {
    if (SDK < 34) return                                 // 无口令直接丢
    if (intent.getSentFromPackage() != MODULE_PACKAGE) return   // 取不到来源也丢
}
```

`getSentFromPackage()` 在部分 ROM / 部分广播路径上返回 `null`，而口令文件只有在
**模块进程已经用 su 生成过一次**之后才存在。两者同时不成立时，**本应用自己发的广播
同样被丢弃**，表现就是用户报的：链接「打开」、手机号「拨号 / 保存到通讯录」、
WiFi「复制密码 / 分享密码」、蓝牙「断开 / 关闭 / 设置」—— 全部无反应。
注意这几个按钮的共性是「必须请 `system_server` 代做」，而它们**同时**失效，
恰好把范围锁死在 `launchReceiver` 上。

7.7 的改法：门禁换成**签名级自定义权限**，由 AMS 在投递时强制，代码里不再做来源判断：

```xml
<permission android:name="io.github.proify.astrafirmament.permission.ISLAND_COMMAND"
            android:protectionLevel="signature" />
<uses-permission android:name="io.github.proify.astrafirmament.permission.ISLAND_COMMAND" />
```

- 两个导出接收器 `IslandDeliverReceiver` / `ApkDiscoveryReceiver` 加 `android:permission`；
- `system_server` 里的 `launchReceiver` 用
  `registerReceiver(receiver, filter, PERMISSION_ISLAND_COMMAND, null, RECEIVER_EXPORTED)`，
  API < 33 走四参重载；
- `system_server` 属 core uid，不受组件权限限制，照常可达；本应用天然持有该权限；
- **口令（`TrustToken`）整套删除**：它解决的是「怎么证明是自己人」，而权限模型已经在
  系统层回答了同一个问题，再叠一层只会把功能搭进去。

### 根因二：卡片寿命与前台服务保护窗口不同源（放久了按钮失效、不自动收起）

三处各算各的：

| 位置 | 7.6 取值 |
| --- | --- |
| 卡片自身 `DismissPolicy` | `lingerSecondsFor(prefs, "link")` ← **硬编码 link** |
| 自动收起 `lingerTask` | `lingerSecondsFor(prefs, featureOf(kind))` |
| `IslandService` 的 `stopSelf` | `lingerSecondsFor(prefs, featureOf(kind))`，0 时兜 60s |

WiFi 卡默认 30 秒，卡片却按 link 的时长留着；前台服务到点先 `stopSelf`，进程随后被冻结 /
回收，卡还在屏上但点它没有任何反应 —— 这就是第三方反馈的「WiFi 卡放久了按钮失效」。
再叠加 WiFi 出卡风暴：`CONNECTIVITY_ACTION` 在某些 ROM 上每 2 秒重发一次，
而 target 里带了每次都变的**网速文本**，3 秒去重永远不命中，于是每 2 秒重新出一次卡、
重排一次 `stopSelf` 定时器；旧的定时器仍在队列里，在最新一张卡的寿命结束前就把服务停掉。
第三方日志 `02_module_log.txt` 里 17:46:56 → 17:47:13 连续 8 轮
`cold start kind=wifi → showCard kind=wifi → start result=OK` 就是这个风暴。

7.7 的改法：

- **单一真值**：`ModulePrefs.lingerSecondsFor(prefs, featureOf(kind))` 夹紧到 `[10, 150]`
  （`clampCardLifeSeconds`，`0` 视为 150，取消「常驻」档）。卡片的 `DismissPolicy`、
  `lingerTask`、前台服务保活窗口全部用这一个值。
- `IslandService` 增加 `generation`：每次 `onStartCommand` 自增，旧的 `postDelayed` 闭包
  发现 `gen != generation` 直接放弃，不再提前停服务；`LinkHub.remainingCardMs()` 给出
  「这张卡还剩多久」，服务按剩余寿命保活，正好在卡消失时收工。
- `LinkHub.dedupDrop` 改用**稳定身份**：WiFi 用 `connected:<ssid>` / `disconnected`，
  不带网速；`system` 侧 `onWifiState` 也做边沿检测（同一身份 60 秒内不重复出卡）。
- WiFi 出卡从「只走透明中转页」改为与其它能力一致的**三通道**（中转页 + 显式服务 +
  定向广播），减少单一通道失效导致的「不出卡」。
- `IslandClient` 坏连接可重建：`NOT_CONNECTED` / `NOT_INSTALLED` 时 `dropClient()`
  置空并 `disconnect()`，下次出卡新建；删除从未赋值的 `pendingSpec` 死分支。

### 其它（同批）

- **日志真的能关**：`ModuleLogger` 的 `debug/info/warn/error` 原来**无条件**
  `module.log()` 写框架日志通道，应用内开关只影响落盘副本 —— 这正是审核说的
  「日志常开且开关关不掉」。改为开关关闭时连框架通道也不写；读取开关加 2 秒缓存
  （`system_server` 里逐条读偏好是性能红线）。
- **日志脱敏**：新增 `shared/Redact.kt`（路径 / URL / MAC / SSID / 蓝牙名 / 歌名歌手 /
  剪贴板长度），`DevLog` 与 `ModuleLogger` 统一过一遍；诊断报告的框架日志改成
  **先按模块过滤**（只留本模块产生的行）再脱敏，不再 `cat` 全文。
- **Root 说明与事实对齐**：`ui_perm_body` 等文案改成逐条列出（读 LSPosed 日志、读 WiFi 密码、
  复制与删除安装包、读 `/data/adb`），并说明未授权 root 时智能文件全链路不可用。
- **关于页「如实说明」重写**：分「权限与数据 / Root 用途 / 日志 / 我们不做的事」四节，
  明确写入「读取全部剪贴板内容（关子开关仍会读取后立即丢弃）」「在音乐应用进程内旁路
  读取网络响应」「root 读取 WiFi 密码、复制与删除安装包」。
- **预测性返回**：拖拽期直接用原始 `progress`（`snap` 不让动画值干扰手感），只有松手 /
  取消才 spring 归零；位移系数 0.28 → 0.55，缩放与透明度同步调整。原来 0.28 +
  `animateFloatAsState` 弹簧平滑，等于「拉很大幅度才退一点」。
- **删掉整套旧 View 页面**：`ui/page/` 下 7 个 Activity + `Toolbar` + `PickerSheet`
  （Compose 壳上线后全部零引用），连同 Manifest 条目与死资源；
  保留 `HomeActivity`（LSPosed 模块设置入口）与 `BaseActivity`。
- **安全规范固化**：`docs/SECURITY.md` 把这次审核条款写成 8 条红线，后续版本按此自查。

7.7 补丁（真机反馈复盘后追加）

- **丢卡出口兜底**：`LinkHub.dismiss()` 原来只写 `client?.end(CARD_ID)`。应用进程被系统
  回收后重开，内存里的 `client` 是空的，而岛上那张卡仍在显示 —— 此时点「知道了 / 忽略」
  表现为「按钮没反应」。改为统一的 `endCard()`：有连接直接关；没有连接就临时建一条
  `IslandClient` 把卡关掉再 `disconnect()`。自动收起（`lingerTask`）走同一条出口。

7.7 UI 手感补丁（用户真机反馈后追加）

- **底栏选中胶囊不再自己跑动画**：原来用上游 `CupertinoNavigationBar`，胶囊按离散的
  `selectedIndex` 自己补间，页面由 `HorizontalPager` 另一套物理驱动 —— 两套动画各走各的，
  于是拖动时「胶囊在追、页面在跑」，出现顿挫与闪跳，松开后还会跳一下。
  改为胶囊位置直接取分页器的连续位置 `currentPage + currentPageOffsetFraction`
  （在 `offset { }` 里按布局阶段读，滑动不触发重组），点标签走
  `animateScrollToPage(spring(dampingRatio=0.74))` 且去掉 `!isScrollInProgress` 守卫，
  动画中途再点一次只是换目标，分页器从当前位置接着走 —— 天然可打断。
  `PagerDefaults.flingBehavior` 的 `snapAnimationSpec` 也换成同一套弹簧，松手不再硬切。
- **药丸宽度与胶囊对齐**：`barWidth` 曾把给屏幕留白的 `OuterInset` 也算进药丸宽度，
  导致格子被推到左边、胶囊与文字错位；改为 `BarPadding * 2 + item * count`，
  并给胶囊的 x 加上 `BarPadding`。
- **预测性返回改成纯平移**：去掉缩放、透明度与圆角动画，只做
  `translationX = size.width * progress`（1:1 跟手，拉多少露多少）。
  副作用是「连续感」回来了：淡入淡出会在拖到一半时闪一下，缩放会让边缘发虚。

7.8 智能文件全链路修复（用户真机反馈：浏览器 / QQ / TIM 下载安装包都不检测）

- **`getSentFromPackage()` 当门禁用 = 自断链路**：`fileSignalReceiver` 里写着
  「API>=34 且来源不是 downloads 进程就丢弃」。这个方法**从 API 35 起对绝大多数
  发送方直接返回 null**，用户机是 Android 17（API 37），于是 downloads 进程发来的
  真信号全被当成伪造丢掉 → 浏览器下载的「发现」信号一个都进不来。
  正确做法是**不靠来源判断，靠内容重验**：信号只带 download id，收到后一律按 id
  重查下载库、重算新鲜度、再过路径白名单；广播里的名字/uri 只当日志。
  伪造一条信号最多让一张本来就会出现的卡晚到，搬不出任何文件。
- **`.apk.1` 只在第三方扫描器里被认**：QQ/TIM 把安装包改名成 `x.apk.1`
  （撞名时 `x.apk (25).1`）防止系统自动安装。`ThirdPartyApkScanner` 早就支持，
  但另外四条路都只写 `endsWith(".apk")`：`LinkHook.isApkDownload`、
  `LinkHook.looksLikeApkInstall`、`ApkInstallWatcher.acceptName` /
  `checkMediaUri` / `offerRecent`（SQL `LIKE '%.apk'`）。统一改成
  `ApkBridge.looksLikeApk()`（正则 `\.apk(?:\.\d+|\s*\(\d+\)\.\d+)$`），
  一处判定、四处复用。副本名也改为去掉 `.1` 尾巴（`x.apk`），安装器才认。
- **`COLUMN_LOCAL_URI` 被当成路径**：Android 10+ 这条列普遍是
  `content://media/external/downloads/<id>`，代码却直接 `Uri.parse(...).path`
  当文件路径交给 su stat —— 必然失败，于是「记录在、卡不来」。改为：只有
  `file://` 才取 path，`content://` 去媒体库查 `_data` 换真实路径。
- **诊断打点**：智能文件每个环节都写 `stage`（落地开始 / stat 成功 / 校验拒绝原因 /
  已出卡），第三方扫描器每轮写「命中候选 N / 不可达目录 M（索引）」，接收器写
  「收到系统候选」。用户报「不检测」时，第一条日志就能分清是「没发现」还是
  「发现了但被自己拒掉」。

## 7.9（72） UI 手感与「智能文件一律不生效」的兜底

- **底栏看不出玻璃（用户：只有一个黑底）**：7.7 把底栏换成自绘玻璃时照搬了上游的
  深色取值（`0x6C7076@36%` + `Black@38%` 的选中胶囊）。上游那套取值是给「画在
  彩色内容之上」的底栏准备的，而星穹的页面底色是近黑渐变——黑膜压在黑底上就是一个
  洞。7.9 的改法：底色提亮一档（`0x7C838C@42%`），选中胶囊从黑膜改成白色薄玻璃
  （`White@13%` + 按压白雾），并给底栏与胶囊各加一道 `1dp` 的竖向渐变描边（上沿亮、
  下沿几乎透明）与顶部斜光——这三样才是「泛光 + 体积感」的来源，缺一样都像灰片。
- **底栏太靠下（用户反馈）**：`AstraTabBar` 只做了 `navigationBars` 内边距，等于贴着
  手势条。7.9 在 `navigationBars` 之上再垫 `AstraDimens.tabBarBottomGap`（10→14dp）。
- **子页面内容被底栏压住（用户反馈「下到极限只能和底栏贴上」）**：`AstraSubPage`
  以前写死 `tabBarClearance = 0.dp`，而能力详情页（歌词胶囊 / 智能上岛 / 各子功能）
  是浮在悬浮底栏之上的，最后一行直接压在底栏下面。7.9 把 `tabBarClearance` 提成参数、
  默认取 `AstraDimens.tabBarClearance`（并把 clearance 的余量 24→32dp），只有真正的
  全屏覆盖层（权限中心 / 开发者选项，此时不画底栏）传 `0.dp`。
- **「智能文件无论在哪里都不生效」**：7.8 修完 system 侧四个怀疑点后用户仍报完全不触发，
  说明断点在跨进程投递这一段（ROM 削广播 / 进程冻结 / hook 没挂上），而且没有日志就能
  直接判死。7.9 加两条**不依赖跨进程投递**的兜底：
  1. `app/ApkMediaPoller.kt`：App 进程每 20s 查媒体库最近 30 分钟落盘的安装包，
     直接走 `SmartApkIntake` 落地出卡（内容指纹去重会吸收系统链路的重复卡）；
  2. `app/ManualApkScan.kt` + 开发者页「扫描安装包测试」：手动扫最近 7 天的安装包
     （媒体库 + QQ/TIM 目录），逐候选写 stage 日志，并回一句话结论给用户。
  两条都不改判据：仍然过 `ApkBridge.isAllowedPath` 白名单与 `ApkIntegrityGuard` 校验。

## 7.10（73）底栏液态玻璃重做 + 审核整改 + 交互修正

### 底栏「没有液态玻璃」（用户连提三版，最终改为直接用上游组件）

- **根因不在参数，在「自己画」这件事本身。** 7.7 起这条底栏一直是自绘：拿 `drawBackdrop`
  自己拼 `vibrancy / blur / lens / highlight / shadow`，再自己调 tint、描边、斜光、亮度下限。
  7.10 前两版分别试过「上游同款参数」和「照抄 Kyant `LiquidBottomTabs` 的三层结构」，
  结果都被用户判为「自创」，观感仍然是一块半透明灰板。
- **原因**：这些观感参数真正生效需要一个内部组件 `CupertinoGlassDefaults`，它是
  **internal** 的——`tint`、`panelTint`、`border`、`selection`、`contentColor`、
  `opaqueMaterial` 以及 blur 16dp / refraction 4dp·8dp 的整套配方都在里面，外部模块
  拿不到，只能凭截图猜。**猜就是自创，自创就一定不像。**
- **改法**：底栏整体换成一个薄壳，玻璃全部交给上游 `zone.ien.hig.CupertinoNavigationBar`。
  这一版是逐行照抄 `JiangNanOvO/ChizuruMizuhara` 的 `ui/kit/AstraTabBar.kt`（总计约 100 行）：
  `AstraTabBar` 只算格子宽度、提供 `backdrop = astraPageBackdrop()`、把标签交给
  `CupertinoNavigationBarItem`，自己不画任何一像素玻璃。滑块、按住拖动换标签、
  玻璃的采样与折射全在组件内部。
- 与 Chizuru 的唯一差别：`OuterInset` 换成参数 `outerInset`（星穹的底栏边距由
  `GlassShell.BAR_INSET` 决定，Chizuru 写死 36dp）。
- **随之删除的自创逻辑**：`position: () -> Float`（胶囊跟 pager 连续位置）与
  `pager: PagerState?`（底栏拖动推 `dispatchRawDelta`）两个参数整个去掉，
  `AstraApp.kt` 的调用点同步收窄，`tabPosition` 一并删除。
  代价要知道：底栏拖动不再「拉着页面同步跟手」，改由上游自己的「按住拖动换滑块、
  松手切换」；第 6 条那个方向反了的问题也随之不存在——不再由我们推分页器。

### 底栏拖动（曾经自创过，现已移除）

7.9~7.10 前两版在底栏外层挂过 `detectHorizontalDragGestures`，把 `dragAmount × (屏宽/底栏宽)`
直接推给 `PagerState.dispatchRawDelta`，想让「底栏走一小段、页面走一整页」，并为此纠结过方向
（`dispatchRawDelta` 的主语是页面，手指往左在底栏上是「上一页」、在页面区是「下一页」）。

**这套逻辑在 7.10 最终版整个删掉**：上游 `CupertinoNavigationBar` 自带「按住滑块拖动换标签」，
再叠一层自己的横向手势会和组件内部的手势抢事件，而且这正是用户点名要删的「自创」。
现在底栏怎么切、滑得多快、松手怎么停，全部由上游组件决定。

### 子设置页侧滑返回时整屏跳变

- **真因在松手那一刻，不在起手。** 原先 `CapabilityPage` 用 `when(page)` 决定
  `PredictivePageStack` 的 parent/child 传谁：三级页松手 → `state.page` 变成
  `PAGE_ISLAND` → `IslandPage` 从 child 槽搬到 parent 槽、`CapabilityHome` 挤进
  parent。**换组合位置 = 整页重建**：`IslandPage` 里两个 `produceState` 重新跑
  （浏览器/安装器列表回到「读取中…」），`rememberLazyListState` 回到顶部——画面在
  松手那一帧整屏跳一下。
- **改法**：`PredictivePageStack` 提成 `parent / child / top` 三个**固定槽位**，只有
  最上面那个非空层跟手平移，下面几层 `translationX` 恒为 0。退栈只让 top 槽消失，
  下面那页从不换槽，也就从不重建。`child` 与 `top` 都可空，`AstraApp.kt` 的调用
  用命名参数，行为不变。

### 「恢复默认时长」后不刷新

- **根因**：`FeaturePage` 的 `own` 来自 `state.featureLinger(key)`，而它末尾读的是
  `ModulePrefs.featureLingerSeconds(prefs, key)`——一次普通的 SharedPreferences 读，
  不是 Compose 状态。写盘成功也不会触发重组，只能退回上一级再进来。
- **改法**：`CapabilityState` 加 `lingerSaved`（`mutableStateMapOf`），初始化与
  `reload()` 时按 `IslandFeatures` 灌入；读取顺序改成「草稿 → 已落盘状态 → 跟随
  默认值」。`commitFeatureLinger` 落盘后回填同一张表并沿用同一套夹紧规则，于是
  「恢复默认时长」按下去当场就变。语义真值仍然只在 `ModulePrefs`，没有第二份真相。

### 测试功能搬出开发者选项

- 开发者选项是给排障用的，不该放功能自测。删掉 `DevPage` 里的「扫描安装包测试」，
  改在能力首页「测试」分组加一张「测试功能」卡片 → `TestPage`：逐项点、逐项回显，
  覆盖链接/手机号/WiFi 两态/蓝牙卡、卡片自动收起、智能文件扫描、广播代发五项。
  歌词胶囊不在此列——它由模块在音乐 App 进程内直接投递，App 侧没有也不该有一个
  「伪造歌词」的广播口；页里如实写明开关读数与实测方法。
- 所有测试只在点击时执行，不开机自测、不发定时广播。

### 文案统一到「如实说明」

- 原先散在各页的警告（Root 用途、日志、剪贴板全文、安装包复制与删除）合并进
  「关于 → 如实说明」，按「读取了什么 / Root 用途 / 日志 / 前提条件 / 我们不做的事」
  五节展开；权限中心与模块描述只留一句准确简述加指路，不再重复整段。
- 智能上岛页的「数据与权限」一节删除，内容并回如实说明。

### 审核点名项复核

- 两个导出接收器（`IslandDeliverReceiver` / `ApkDiscoveryReceiver`）的发送方校验
  全部落在 manifest 的**签名级自定义权限**上，由 AMS 在分发前拦截；不再有任何
  「取不到来源就放行」的运行时判断。system_server 侧注册的 LAUNCH 接收器同样用
  `registerReceiver(..., PERMISSION_ISLAND_COMMAND, ...)`。
- 日志：`DevLog` 与 `ModuleLogger` 都是**默认关、关掉一条不写**；只保留
  `link-hook <版本> loaded` 一行（权限中心靠它判模块是否加载，不含任何用户数据）。
  写入前统一过 `Redact`，只抹「完整路径 / 完整网址 / WiFi 名称 / 蓝牙名与 MAC /
  歌名歌手」六类，其余照常详细记录。

### 智能文件补全来源

- **目录缺失**：`ThirdPartyApkScanner` 原先只有 3 条目录，没有 `/sdcard/Download`
  （adb push、电脑传输、多数下载器都落这里），也没有微信；补到 16 条，并给
  Quark / UCDownloads / QQBrowser / BaiduNetdisk / Thunder / Chrome 各自加目录。
- **两套后缀正则**：扫描器里还有一份只认 `.apk / .apk.1 / .apk (25).1` 的老正则，
  `.apk.1.1`、`.apk.2` 一律不认；统一到 `ApkBridge.looksLikeApk`。
- **媒体库粗筛写错**：`LIKE '%apk'`（注释说的却是 `%apk%`），`x.apk.1` 因为末尾是
  `.1` 被滤掉；改成 `%apk%`。
- **静默失败全部补打点**：`stage=download` / `stage=receiver` / `stage=intent` /
  `stage=后缀` 逐环记录，用户报「不检测」时第一条日志就能分清是「没发现」还是
  「发现了但被自己拒掉」。
- **MIME 判据放宽**：文件管理器打开安装包时常把 MIME 报成 `octet-stream` 或空串，
  原先直接不进链路；现在含糊 MIME + `file/content` 也放行，交给真实
  `DISPLAY_NAME` 判后缀，不像安装包就跳过、不猜名字。
- **不做**「分享到本应用」（`ACTION_SEND`）：那需要一个 `exported=true` 且拿不到
  签名权限的接收器，外部应用根本送不进来，反而违反 `docs/SECURITY.md` 第 1 条。

## 7.11（74）死卡保活重写 + 中转页归零 + 智能文件两方案重构 + SDK 0.1.2

### 岛上卡片「按钮点不了」：不是按钮坏了，是前台服务提前收工

- **现象**：卡片正常弹出，点任何按钮都没反应；重启模块后偶发又能用。
- **真因（代码级）**：一次出卡被**三条通道**同时投递（`LinkHook.outCard` 的
  「冷启动 Activity + 定向广播 + system 直呼 `IslandService.connectAndShow`」），
  每条通道都会 `startService`，于是同一个请求会走 2~3 次 `onStartCommand`。
  旧模型用「最新世代（generation）才允许 stopSelf」收工，而被开关拦下或撞上
  3 秒去重的那次请求，`showCardFromModule` 的 `onDone` 会**立即**回调；
  此时卡片还没出（`cardShownAt=0`），`remainingCardMs()` 返回 0，
  于是最新的那次世代立刻 `stopSelf(startId)` —— 前台服务在真正出卡的那条通道
  还在退避重试（`RETRY_DELAYS_MS` 合计约 10 秒）时就被收掉了。
  前台服务一停，进程失去保护、随时可被冻结/回收，而卡片随后仍然弹出：
  按钮回调是宿主 Binder 打进应用进程的，进程里没人接 = 点了没反应。
- **改法**（`LinkHub.kt` + `IslandService.kt`）：
  - `showCardFromModule(ctx, kind, target, onDone: ((shown: Boolean) -> Unit)?)`：
    「被开关挡下 / 被去重丢掉」回 `false`，真正 `island.start()` 返回 OK 才回 `true`；
    `startWithRetry` 改为返回 `Boolean`。**把「没出卡」当「出卡成功」是这一版之前
    一切死卡的共同起点。**
  - `IslandService` 换成**待办计数 + 1 秒看门狗**：每个出卡请求进来先
    `pendingShows.incrementAndGet()`，回调返回时减一；看门狗每秒读
    `pendingShows` 与 `LinkHub.remainingCardMs()`，**两者都归零才 stopSelf**，
    否则继续续命。删除 `generation` / `scheduleStop(startId)` 那套世代语义
    ——多通道重复投递不再是相互踩脚的理由。
  - 兜底预算 `MAX_WATCH_TICKS = 240`（约 4 分钟）：任何回调丢失的异常路径都不会
    留下永不退场的常驻服务；正常路径上卡片收起后 1 秒内自行收工。

### 「点了上岛，整个屏幕都点不了」：中转页的起始窗口

- **现象**：检测到链接/安装包出卡后，屏幕上一段时间内**哪里都点不动**，
  用户还看到「透明界面有背景了」。
- **真因**：`IslandColdStartActivity` 是每次出卡都要起的全屏透明中转页。
  Android 12+ 会为每个新 Activity 画**起始窗口（SplashScreen）**，它按主题的
  `windowSplashScreenBackground` 铺满一屏（默认取窗口背景，深色主题就是一块深色板），
  用户看到的「透明界面有后台」就是它。起始窗口是**另一个窗口**，Activity 里
  `FLAG_NOT_TOUCHABLE` 管不到它；只要它还在，触摸就落在它上面。
- **改法**（三处，缺一不可）：
  - `themes.xml` 拆出 `Theme.LyricProvider.Transparent.Base` 并加
    `windowDisablePreview=true`（关掉老系统的预览窗口）；`values-v31/themes.xml`
    把 `windowSplashScreenBackground` / `windowSplashScreenIconBackgroundColor`
    置为透明、`windowSplashScreenAnimatedIcon` 指向新增的
    `drawable/ic_splash_transparent.xml`（1 个透明色，不是图）。
  - `IslandColdStartActivity.onCreate` 首行 `makeWindowInvisible()`：
    窗口背景设透明、`clearFlags(FLAG_DIM_BEHIND)`、`addFlags(FLAG_NOT_TOUCHABLE
    or FLAG_NOT_FOCUSABLE)` —— 从窗口层面就**不可能**吃掉一次触摸。
  - `finishSafely()` = `finishAndRemoveTask()`（失败退回 `finish()`），并用
    800ms 看门狗兜底；`startFromSystem` 对「同 kind+target 在 800ms 内」重复启动
    直接跳过，避免三条通道把中转页闪三次。

### 智能文件：两条链路分别「关注下载中」与「只认新增」

用户给的思路（下载中先关注、下载完成再出卡；特定目录新增安装包才出卡）对应
两条完全不同的链路，这一版各补一刀：

- **下载完成链路（浏览器/系统下载器走 DownloadManager）**：`LinkHook` 在
  downloads 进程多挂了一个 `DownloadProvider.insert` 的 hook。下载一开始
  （MIME 或 URL 后缀像 APK）就把 `_id` 记进 `followedDownloads`（TTL 2 小时、
  上限 16 条），`update` 报 SUCCESSFUL 时把「刚关注过的 id」一并并入候选集。
  之前只认 `update` 参数里带的 id —— 有些浏览器先建记录、再分块写，成功那次
  的 update 不带 id，就会整条漏掉。
- **目录新增链路（TIM/QQ/微信这类自己落文件的）**：`ThirdPartyApkScanner` 重构。
  - **来源目录可配置**：默认 17 条（QQ/TIM 新版落 `Android/data`、老版落
    `tencent/`、微信/企业微信、`Download`、夸克/UC/QQ浏览器/百度网盘/迅雷/Chrome），
    设置页可增删；新增走 SAF 目录树选择器，只接受 `primary:` 卷并转成
    `/storage/emulated/0/...` 绝对路径，路径统一过 `ModulePrefs.normalizeApkSourceDir`
    （拒 `..`、引号、换行 —— 它们会被拼进 su 命令）。
  - **扫描层级**：仅目录本身 / 向下 2 / 3 / 5 层 / 不限，默认 3（QQ 偶尔多套一层）。
    一次 `su` 跑完全部目录：`find '<dir>' [-maxdepth N] -type f -iname '*.apk*'
    -exec stat -c '%s %Y %n' {} +`，目录用 `##<序号>` 分隔、每目录再打一行
    `@ok/@missing/@denied`，**0 命中、目录不存在、没权限三种情况必须分得开**。
    toybox `find` 不支持 `-printf`，`-exec stat` 才靠得住；路径取行内剩余整段，
    带空格也不破坏解析。
  - **只认新增**：`ApkScanBaseline` 持久化基线（独立偏好 `xinghe_apk_baseline`）。
    某目录**第一次**被扫到时，里面已有的文件只登记不出卡（本机装好就有 20 个
    安装包的人不会被刷屏）；之后「路径 + size + mtime」没登记过的才算新增。
    两轮 size 仍在变化（浏览器还在写）不投递，留给下一轮。
- 「不 hook TIM/QQ」的约束仍然守：这两条链路都不注入对方进程，只在系统下载器
  （`com.android.providers.downloads`）旁听，以及用 root 只读 `stat` 目录。

### 接入库升到 astraisland SDK 0.1.2

- `app/libs/astraisland-sdk-0.1.2.aar`（sha256 `1e7f71ca…f14`），旧 0.1.1 删除。
  逐类 `javap` 比对：旧 85 个类全部保留、仅新增 6 个 `ButtonLayout*`，项目用到的
  13 个类零缺失，**调用方代码零改动**。0.1.2 唯一的线格式新增是卡片按钮
  `buttonLayout`（perRow / rowFill / loneFill / loneAlign），0.1.1 的线格式仍然被接受。
- 接入库规范（01~10 + LICENSE + SHA256SUMS + 标注「不可清理」的 README）入库到
  `docs/island-sdk/`，官方 sample 落在 `工作/资料/文档/island-sdk-sample/`
  （资料目录不参与发布）。

### 测试页精简（审核要求）与死代码清理

- `TestPage` 190 行 → 75 行：只留「说明 / 刷新星流连接状态 / 链接卡片 / 手机号卡片」。
  删掉的一律是**会真的动用户设备**或**审核敏感**的项：用浏览器打开链接、WiFi 设置页、
  蓝牙断开、**关闭蓝牙**、蓝牙设置页、WiFi 两态卡、蓝牙卡、卡片时长自检、
  智能文件扫描测试。测试只做「弹一张卡」这一件事，点按钮即执行、不开机自测、
  不发定时广播。
- 随之删除：`app/ManualApkScan.kt`（整文件，0 引用）、`SmartApkIntake.runManual`
  与贯穿三个函数的 `forceFresh` 形参/分支（唯一调用方就是被删的手动扫描）。

### CI 编译校验（2026-11-07）

GitHub Actions（私有构建仓库 `2979208016/AstraFirmament-BuildCheck`，只做编译校验，
不参与发布）在提交 `f7fddcf` 上 `:app:assembleDebug` 通过。此前两轮失败暴露并修掉：

1. `hook/LinkHook.kt` 的 `DownloadManager.COLUMN_MIME_TYPE` 在 compileSdk 37 的公开桩里
   已取不到 → 改用同义的 `COLUMN_MEDIA_TYPE`（insert 阶段该列常为空，后面还有
   `COLUMN_URI` 兜底，判定语义不变）；
2. `ui/shell/CapabilityPage.kt` 里手写 `fun setScanDepth` 与委托属性 `var scanDepth`
   的隐式 setter 撞同一个 JVM 签名（`setScanDepth(I)V`）→ 改名 `applyScanDepth`。
   **注意**：这类「委托属性 + 同名 setter 方法」的冲突脚本查不出来，只会在编译期报，
   改动状态容器时要顺手看一眼。

## 补充（2026-11-17）：副本 Provider 未授权可读修复——URI 能力化

**问题**：副本 Provider `exported=true` 且无读取权限，`openFile`/`query` 不校验调用方，
任何应用知道副本地址（authority + 副本名可枚举/可猜）即可直接读暂存 APK。

**为什么不能走标准 FileProvider 授权链**（AOSP `UriGrantsManagerService` 源码考证）：

1. `exported=true` 且无 readPermission 时，任何 uid 都被判定「已持有」权限，
   `checkGrantUriPermission` 对 basic grant 直接返回 -1，**从不记录授权**——所以
   「Provider 内 `checkUriPermission` 做门禁」查不到任何记录，会把合法安装器也挡死。
2. `exported=false` 时，发起授权的是 system_server（卡片按钮由 system 代发 intent），
   而 AOSP 明确拒绝：`"For security reasons, the system cannot issue a Uri permission
   grant ... use startActivityAsCaller() instead"`，SYSTEM_UID 调用方不记录授权——
   安装器会被 AMS 层直接挡死。

**改法：URI 自带访问口令**。URI 形状从 `/apk-incoming/<副本名>` 改为
`/apk-incoming/<token>/<副本名>`，`token = HMAC-SHA256(本机随机密钥, 副本名)` 截断
12 字节；密钥首次使用时生成，存 `filesDir/apk_provider.key`（仅本进程可读）。
`exported=true` 不动，但「知道副本地址」不再够用——token 只随卡片链路发放，
枚举副本名也伪造不出可读 URI；比对用 `MessageDigest.isEqual` 常量时间，防时序侧信道。

- `ApkBridge.uriFor` 插入 token 段（只 App 进程调用；system 侧只透传 URI，不算 token）；
- `ApkIncomingProvider.allowedFile` 要求恰好 3 段路径并校验 token，原有的只读 /
  .apk 白名单 / canonical path 校验保留；
- `isOurUri`（首段检查）与 `lastPathSegment` 消费方（删除/展示名）无需改动；
- **兼容性**：升级前已发出的旧卡片携带无 token URI，升级后点不动——卡片短命，可接受。

## 补充（2026-11-18）：内部 ACTION_LAUNCH 广播泄露修复——发送端限定接收权限

**问题**：App → system_server 的 `ACTION_LAUNCH` 使用无 `receiverPermission` 的
`sendBroadcast`。接收端虽然用 `senderPermission` 限制了发送方，但该参数只校验发送方，
不限制收听方；同设备任意应用注册同 action 即可收到明文载荷：

- 复制/分享 Wi-Fi 密码：`EXTRA_TARGET` 就是密码本身（`KIND_CLIP` / `KIND_TEXT_SHARE`）；
- 暂存安装包就绪：`EXTRA_TARGET` 含带 HMAC 访问凭证的副本 content URI，
  `EXTRA_TOKEN` 另带一次性出卡口令。

**改法**：发送端统一传入模块自身的签名级接收权限
`io.github.proify.astrafirmament.permission.ISLAND_COMMAND`
（`LinkHub.sendLaunch`、`SmartApkIntake` 的 `KIND_READY` 与 `KIND_ABORT`）。
Android 的 `Context.sendBroadcast(intent, receiverPermission)` 只在接收方持有该权限时投递：
本签名应用与 system_server（core uid）可收，普通第三方应用无法注册收听。接收端原有
`senderPermission` 保持不变，形成发送方、接收方双向校验。

- **不改为 `setPackage("android")`**：ColorOS 16 实测不投递给 system_server 内动态注册的
  接收器，会导致所有卡片按钮失效；
- **不做 Binder 改造**：现有单向一次性广播 + 签名权限已满足最小权限原则，改动面更小；
- **兼容性**：不改变 action、extra、注册方式与调用时序，只收窄收听范围；
- **其他广播复核**：`ApkDiscoveryReceiver` / `IslandDeliverReceiver` 为显式组件且 manifest
  声明签名级 `android:permission`；`TestPage` 与出卡广播均 `setPackage` 到本包，均不存在
  同设备旁听路径。
