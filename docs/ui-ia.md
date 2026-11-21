# 星穹 v6.0 界面信息架构（IA）

> 事实基线：`version.properties` versionCode=60 / versionName=6.0；`Constants.kt:20` MODULE_VERSION="6.0"。
> 本文只做 IA 与交互路径设计，不含实现代码。所有偏好 key 均取自 `settings/ModulePrefs.kt:17-49` 与全仓 grep 核实，未核实的不写。

---

## 1. 现状盘点

### 1.1 页面清单（7 个 Activity + 1 个抽屉）

| 文件 | 角色 | 入口 | 现状问题 |
|---|---|---|---|
| `ui/page/HomeActivity.kt` | 首页：2 张能力卡 + 「更多能力」灰卡 + 4 行万能入口（权限中心/设置/关于/开发者）+ 赞助卡 | 桌面 alias `.ui.LauncherAlias`、LSPosed `MODULE_SETTINGS` | 一个页面承担 5 种职能；赞助卡与能力无关却占据首屏 |
| `ui/page/SettingsActivity.kt` | 设置：模块总开关、隐藏图标、LSPosed、创作者/免费公益两行 | 仅显式 Intent（`exported=true` 无 filter） | 与「关于」内容重叠；无底栏，用户看不到其他页 |
| `ui/page/AboutActivity.kt` | 关于：简介卡 + Root 用途卡 | 首页第 3 行 | 内容被 `ui_about_body` 混装（简介+技术行为说明在同一段） |
| `ui/page/DevActivity.kt` | 开发者选项：日志开关、状态、分享报告、清除 | 首页第 4 行 | 层级过深（首页 → 开发者 → 开关），且首页误把「开发者」当常用入口 |
| `ui/page/PermissionCenterActivity.kt` | 权限中心：Root / 模块加载 / 星流连接 | 首页第 1 行 | 位置过前；只在出问题时才需要 |
| `ui/page/LyricDetailActivity.kt` | 歌词胶囊详情：状态 + 开关、适配应用行、一行说明 | 首页歌词卡 | **整页只有 1 个可写偏好**，其余是只读说明 |
| `ui/page/LinkDetailActivity.kt` | 智能上岛详情：总开关 + 5 子开关 + 默认应用 2 行 + 默认时长 + 每功能时长面板 + 3 段小字 | 首页上岛卡 | 5 个子能力全是「行内一行」，页面长且无分组；默认时长卡夹在中间 |
| `ui/page/SheetActivity.kt` | 底部抽屉：所有长说明文字 | 多处 | 保留 |
| `ui/page/PickerSheet.kt` | 选择抽屉：浏览器/安装器/清理周期 | 多处 | 保留 |

### 1.2 偏好项全量（19 个 `KEY_*` 常量 + 5 个动态 linger 键，全部经 `ModulePrefs.kt` 核实）

| key | 类型 | 默认 | 语义 | 写入点 |
|---|---|---|---|---|
| `module_enabled` | Bool | true | 模块总开关 | SettingsActivity |
| `hide_launcher_icon` | Bool | false | 隐藏桌面图标 | SettingsActivity |
| `cap_lyric_enabled` | Bool | true | 歌词胶囊 | Home / LyricDetail |
| `cap_link_enabled` | Bool | true | 链接助手（**旧键，被 `isSmartIslandEnabled` 回落读取**） | Home 曾写、现 UI 已改写上岛键 |
| `cap_smart_island_enabled` | Bool | 回落 `cap_link_enabled` → true | 智能上岛总开关 | Home / LinkDetail |
| `cap_phone_enabled` | Bool | true | 手机号上岛 | LinkDetail |
| `cap_file_installer_enabled` | Bool | true | 智能文件（APK 下载弹岛） | LinkDetail |
| `cap_wifi_enabled` | Bool | true | WiFi 助手 | LinkDetail |
| `cap_bluetooth_enabled` | Bool | true | 蓝牙岛 | LinkDetail |
| `dev_log_enabled` | Bool | false | 开发者日志落盘 | DevActivity |
| `link_browser_mode` | String | "system" | 浏览器策略 `system`/`pinned`/`ask` | LinkDetail |
| `link_browser_package` | String | "" | 固定浏览器包名 | LinkDetail |
| `installer_mode` | String | "system" | 安装器策略（同三值） | LinkDetail |
| `installer_package` | String | "" | 固定安装器包名 | LinkDetail |
| `card_linger_seconds` | Int | 30 | 上岛卡默认显示时长（0=常驻） | LinkDetail |
| `linger_sec_<feature>` | Int | -1 | 按功能覆盖时长，`feature ∈ {link, phone, file, wifi, bt}`；-1=跟随默认 | LinkDetail |
| `apk_clean_days` | Int | 3 | 暂存副本自动清理天数（0=不按时间清理） | LinkDetail |
| `cur_kind` / `cur_target` | String | — | 单卡目标持久层（运行态，非用户偏好） | LinkHub / IslandService |

**没有的 key（不要在设计里假设存在）**：
- 任何 `per-host` 歌词宿主开关（Spotify / 网易云 / 酷狗 …）——宿主只由 LSPosed 作用域 + `module_enabled && cap_lyric_enabled` 两道闸门控制（`LocalLyricProvider.kt:206-207`、`SpotifyLyricProvider.kt:846-847` 等 12 处同款判定）。
- 任何歌词专属时长 key（时长只作用于上岛卡：`IslandService.kt:56`、`LinkHub.kt:226`）。
- 任何「首次使用 / 引导已完成」标记。

### 1.3 能力 → 子能力 → 可否单独设置 → key → 现所在页面

| 主能力 | 子能力 | 可否单独设置 | key | 现在的位置 |
|---|---|---|---|---|
| 歌词胶囊 | （仅主开关） | — | `cap_lyric_enabled` | 首页卡 + 歌词详情页开关（两处） |
| 歌词胶囊 | 宿主：本地缓存 9 家（酷狗/酷狗概念版/波点/QQ音乐/酷我/网易云/OPPO音乐/汽水/洛雪） | **否** | 无 | 仅一张「适配的应用」行 → Sheet 长文 |
| 歌词胶囊 | 宿主：独立 provider 8 家（Apple Music/Poweramp/QQ音乐HD/Salt/MusicFree/Gramophone/Symfonium/Spotify） | **否** | 无 | 同上（`Constants.kt:112-124`） |
| 歌词胶囊 | 宿主：MeloYou（`com.music`） | **否**，且**故意不在默认作用域** | 无 | 同上（`arrays.xml:14-21` 记载禁止原因） |
| 智能上岛 | 总开关（联动 5 子项） | 是 | `cap_smart_island_enabled` | 首页卡 + 上岛页 |
| 智能上岛 | 链接助手 | 是 | `cap_link_enabled` | 上岛页 |
| 智能上岛 | 手机号上岛 | 是 | `cap_phone_enabled` | 上岛页 |
| 智能上岛 | 智能文件 | 是 | `cap_file_installer_enabled` | 上岛页（含暂存清理 2 行） |
| 智能上岛 | WiFi 助手 | 是 | `cap_wifi_enabled` | 上岛页 |
| 智能上岛 | 蓝牙岛 | 是 | `cap_bluetooth_enabled` | 上岛页 |
| 智能上岛 | 链接助手 › 默认浏览器 | 是 | `link_browser_mode` + `link_browser_package` | 上岛页「默认应用」卡（与安装器合卡） |
| 智能上岛 | 智能文件 › 默认安装器 | 是 | `installer_mode` + `installer_package` | 同上 |
| 智能上岛 | 智能文件 › 暂存副本清理 / 自动清理 | 是 | `apk_clean_days`（清理动作无 key） | 上岛页 |
| 智能上岛 | 每功能显示时长 ×5 | 是 | `linger_sec_<feature>` | 上岛页行尾箭头内联面板 |
| （全局） | 默认显示时长 | 是 | `card_linger_seconds` | 上岛页中部 |
| （全局） | 模块总开关 / 隐藏图标 | 是 | `module_enabled` / `hide_launcher_icon` | 设置页 |
| （全局） | 开发者日志 | 是 | `dev_log_enabled` | 开发者页 |

### 1.4 现状缺陷（影响 IA 的设计约束，均带证据）

1. **通配联动越界（缺陷）**：`LinkDetailActivity.kt:94-100` 用反射扫所有 `KEY_*` 常量、凡值以 `cap_` 开头就一起写。`cap_lyric_enabled`（`ModulePrefs.kt:19`）同样以 `cap_` 开头 → **关掉「智能上岛」总开关会把「歌词胶囊」一起关掉**。IA 把两者设为并列主能力后，这条必须收敛为上岛树的 5 项白名单。
2. **首页歌词卡状态自相矛盾**：`HomeActivity.kt:50-58` 的 `checked = isEnabled && isLyricEnabled`，但 `onToggle` 只写 `KEY_LYRIC`。模块总开关关闭时，用户把卡打开 → 下次进页面仍是「关」。
3. **过度承诺文案**：`cap_lyric_on` =「正在推送歌词」、`lyric_status_ready` =「已生效」。模块不监听宿主播放态，无法判定「正在推送」。IA 验收必须禁用这类文案。
4. **模块读不到自己的 LSPosed 作用域**：宿主是否被勾选，模块侧无 API 可查（现有代码只按包名 hook）。UI 只能显示「已安装/未安装」，不得显示「作用域已勾选」。
5. **图标资源只有 6 个**：`res/drawable` 中图标仅 `ic_chevron / ic_heart / ic_note / ic_open / ic_person / ic_star`。子能力行若要专属图标（WiFi/蓝牙/手机/文件）属**新增资源**，不在本文档范围内，验收时不得引用不存在的 drawable。
6. **设置页与关于页职责重叠**：`SettingsActivity` 底部有「创作者 / 免费公益」两行，`AboutActivity` 又有一份简介，两处都能开 Sheet。

---

## 2. 新信息架构

### 2.1 骨架

```
底栏（3 段，固定顺序）
├── 能力  ← 默认 tab；主能力卡 → 子能力页
├── 设置  ← 模块级开关 / 运行状态 / 工具 / LSPosed
└── 关于  ← 简介 / 如实说明 / 赞助作者

二级页（从 tab 内推入，带 Toolbar 返回）
├── 歌词胶囊（子能力页）
├── 智能上岛（子能力页）
├── 权限中心
└── 开发者选项
底部抽屉（SheetActivity / PickerSheet）：所有长说明、所有选择器
```

实现形态约定：**不引入 Fragment、不引入新依赖**（与 `LocalLyricProvider` 等处的「禁引新依赖」一致）。三 tab 用 `BaseActivity.wrapPage()` + 自绘 View 容器 + 显隐切换；`HomeActivity` 就地改造成 tab 壳，`LauncherAlias` 与 `MODULE_SETTINGS` 均指向它 → **桌面图标显隐（`BaseActivity.setLauncherIconHidden` 用 `ui.LauncherAlias`）零改动**。

### 2.2 能力 tab（默认页）

从上到下的卡片顺序：

| # | 元素 | 类型 | 内容 |
|---|---|---|---|
| 1 | 页头 | 非卡 | 标题「能力」+ 一行副标题「星流增强插件」+ 版本 pill |
| 2 | 模块停用横幅 | 条件条 | 仅 `module_enabled=false` 时出现：「模块已停用，全部能力不生效」+ 右侧「去开启」→ 设置 tab |
| 3 | 歌词胶囊 | 主能力卡 | 见 §2.2.1 |
| 4 | 智能上岛 | 主能力卡 | 见 §2.2.2 |
| 5 | 更多能力 | 占位卡（禁用） | 灰卡、`alpha=0.5`、不可点、文案「后续更新会在这里出现」 |

#### 2.2.1 歌词胶囊卡

| 槽位 | 内容 | 数据来源 |
|---|---|---|
| 图标 | `ic_note` + accent 底色徽章 | 现有 |
| 标题 | 「歌词胶囊」 | `cap_lyric` 字符串 |
| 状态摘要（一行） | 见下方枚举 | `module_enabled` + `cap_lyric_enabled` |
| 开关 | 直写 `cap_lyric_enabled` | 现有 |
| 右下提示 | 「点卡片进入设置 ›」 | `ui_card_tap_hint` |

摘要枚举（只允许这 3 种，删掉「正在推送歌词」）：

| 条件 | 摘要 |
|---|---|
| `module_enabled=false` | 模块已停用 |
| `cap_lyric_enabled=false` | 未开启 |
| 两者皆开 | 已开启 · 17 个播放器适配 |

（17 = 本地缓存 9 + 独立 provider 8，见 `Constants.kt:125-196` 与 `Constants.kt:112-124`；MeloYou 不计入「适配」，它是需手动勾作用域的兜底通道。）

#### 2.2.2 智能上岛卡

| 槽位 | 内容 | 数据来源 |
|---|---|---|
| 图标 | `ic_open` + ok 底色徽章 | 现有 |
| 标题 | 「智能上岛」 | `cap_link` 字符串 |
| 状态摘要（一行） | 见下方枚举 | 总开关 + 5 子开关计数 + `LinkHub.stateName()` |
| 子能力点条 | 5 个 8dp 圆点，固定顺序 link / phone / file / wifi / bt；开=ok 色，关=off 色 | 5 个 `cap_*` |
| 开关 | 直写 `cap_smart_island_enabled` | 现有 |
| 右下提示 | 「点卡片进入设置 ›」 | 现有 |

摘要枚举（按优先级取第一条命中）：

| 条件 | 摘要 |
|---|---|
| `module_enabled=false` | 模块已停用 |
| 总开关关 | 未开启 |
| `LinkHub.stateName()=="NOT_INSTALLED"` | 未安装星流 |
| `"REJECTED"` | 被星流拒绝 |
| 其他 | 已开启 · {N}/5 项在用 |

**不做「能力卡内联展开子开关」**。理由：卡身点击已用于进详情，再加一个展开命中区会让一张卡出现 3 个触控目标（开关 / 展开 / 进入），且展开后整页高度跳动。用户要求的「常用贴着用户」由两点满足：① 5 个子开关在子能力页首屏，2 步可达；② 每个子能力行尾箭头就地展开专属设置面板（§2.4）。

### 2.3 主能力 / 子能力划分（权威表）

| 层级 | 标识 | 名称 | 开关 key | `linger` feature | 图标 |
|---|---|---|---|---|---|
| 主能力 | lyric | 歌词胶囊 | `cap_lyric_enabled` | 无 | `ic_note` |
| 主能力 | island | 智能上岛 | `cap_smart_island_enabled` | 无 | `ic_open` |
| 子能力 | link | 链接助手 | `cap_link_enabled` | `link` | 无（文字行） |
| 子能力 | phone | 手机号上岛 | `cap_phone_enabled` | `phone` | 无 |
| 子能力 | file | 智能文件 | `cap_file_installer_enabled` | `file` | 无 |
| 子能力 | wifi | WiFi 助手 | `cap_wifi_enabled` | `wifi` | 无 |
| 子能力 | bt | 蓝牙岛 | `cap_bluetooth_enabled` | `bt` | 无 |
| 子能力 | host×17 | 歌词宿主适配 | **无**（只读） | 无 | 无 |

歌词只做「状态 + 宿主清单」不硬造子开关；上岛严格 5 个子能力，与代码闸门一一对应（`LinkHub.kt:161-169`）。

### 2.4 歌词胶囊子能力页（改造 `LyricDetailActivity`）

| 顺序 | 卡片 / 区块 | 内容 |
|---|---|---|
| 1 | 状态卡 | 状态点 + 一行状态 + 开关（保留现有结构，改文案） |
| 2 | 宿主适配卡 | 三组，每组一个小标题 + 行列表（见下） |
| 3 | 怎么用 | 现有 `lyric_howto_body` 一行小字（不新增长文） |

状态卡文案（诚实版）：

| 条件 | 文案 | 颜色 |
|---|---|---|
| `module_enabled=false` | 模块已停用，歌词不会推送 | warn |
| `cap_lyric_enabled=false` | 已关闭 | text_hint |
| 两者皆开 | 已开启（播放器播放时推送） | ok |

宿主适配卡分组与行内容：

| 分组 | 成员（显示名 / 包名） | 行右侧 |
|---|---|---|
| 本地缓存适配（9） | 酷狗音乐 `com.kugou.android` / 酷狗概念版 `com.kugou.android.lite` / 波点音乐 `cn.wenyu.bodian` / QQ 音乐 `com.tencent.qqmusic` / 酷我音乐 `cn.kuwo.player` / 网易云音乐 `com.netease.cloudmusic` / OPPO 音乐 `com.heytap.music` / 汽水音乐 `com.luna.music` / 洛雪音乐 `cn.toside.music.mobile` | 状态点：已安装=ok / 未安装=off + 文案「未安装」 |
| 独立适配（8） | Apple Music `com.apple.android.music` / Poweramp `com.maxmpz.audioplayer` / QQ 音乐 HD `com.tencent.qqmusicpad` / Salt Player `com.salt.music` / MusicFree `fun.upup.musicfree` / Gramophone `org.akanework.gramophone` / Symfonium `app.symfonik.music.player` / Spotify `com.spotify.music` | 同上 |
| 兜底通道（1） | MeloYou `com.music` | 状态点 + 一行警示小字「默认作用域不含此应用；勾选后可能导致开机异常，见说明」 |

每个宿主行可点 → `SheetActivity` 抽屉，内容 = 该宿主的歌词来源一句话 + 「需在 LSPosed 勾选 <包名> 并强制停止该应用」。这是本页唯一的「展开面板」直达。

明确不做：per-host 开关（无 key，见 §4 N2）；「作用域已勾选」状态（无 API，见 §1.4-4）。

### 2.5 智能上岛子能力页（改造 `LinkDetailActivity`）

| 顺序 | 卡片 | 内容 |
|---|---|---|
| 1 | 总开关卡 | 「智能上岛总开关」+ 说明「开：5 个子功能一并开；关：一并关」+ 开关（通配**仅这 5 项**） |
| 2 | 子能力卡（5 行，按使用频率排序） | 链接助手 → 手机号上岛 → 智能文件 → WiFi 助手 → 蓝牙岛 |
| 3 | 默认显示时长卡（兜底） | SeekBar 0..120，0=常驻；文案「未单独设置的功能都用这个时长」 |
| 4 | 连接状态行 | 「星河岛连接：已连接/未安装星流/未就绪/被拒绝/被星流拒绝」→ 点进权限中心 |
| 5 | 说明小字 ×3 | 作用域 / 隐私 / 清后台（沿用现有三段，不合并、不改写） |

子能力行结构（5 行同构）：

```
[标题 + 一行说明]            [开关]  [48dp 展开箭头]
└─ 展开面板（默认收起，兄弟节点，展开时下方内容整体下移）
   1) 显示时长：当前值 + SeekBar(0..120) + 「恢复默认」胶囊（未单独设置时置灰）
   2) 该子能力的专属项（仅 link / file 有）
```

各子能力的专属项归属（**每个偏好只有一个归属处**）：

| 子能力 | 面板专属项 | 迁入自 |
|---|---|---|
| 链接助手 | 默认浏览器（`link_browser_mode` + `link_browser_package`） | 旧「默认应用」卡 |
| 手机号上岛 | 无 | — |
| 智能文件 | 默认安装器（`installer_mode` + `installer_package`）<br>清理暂存副本（动作 + 占用摘要）<br>自动清理（`apk_clean_days`） | 旧「默认应用」卡 + 卡内 2 行 |
| WiFi 助手 | 无 | — |
| 蓝牙岛 | 无 | — |

交互规则（写进验收）：
- 点行内任意处（除开关、除箭头）= 切换该子开关；
- 点开关 = 只切换开关，不展开；
- 点箭头 = 只展开/收起面板，不切开关（现有 `lingerToggle` 已是独立 48dp 命中区，保持不变，箭头 90°↔270°）。

### 2.6 设置 tab

| 顺序 | 卡片 | 行 | 右侧 |
|---|---|---|---|
| 1 | 模块 | 启用模块（`module_enabled`） | 开关 |
| 1 | 模块 | 隐藏模块桌面图标（`hide_launcher_icon`）+ 副标题「隐藏后桌面无图标；LSPosed 模块设置仍可打开」 | 开关 |
| 2 | 运行状态 | 模块加载 / Root 权限 / 星河岛连接（3 行摘要） | 状态文案（ok/hint 色）+ 整卡点进「权限中心」 |
| 3 | 工具 | 开发者选项 | 摘要「日志已开 / 日志已关」+ `›` |
| 3 | 工具 | 启动 LSPosed | `›` |
| 4 | 页脚（非卡） | `v6.0` · 包名 | 小字 |

设置 tab **不放**：任何功能开关（在能力 tab）、关于/赞助（在关于 tab）。这是现状首页那 4 行万能入口的替代。

### 2.7 关于 tab

| 顺序 | 卡片 | 内容 |
|---|---|---|
| 1 | 项目简介 | 一句话定位（星流增强插件，把歌词与链接送进星河岛）+ 版本 + 创作者（yiyi / QQ 2979208016）+ 许可（MIT，禁止倒卖） |
| 2 | 如实说明（独立卡） | 标题「如实说明」+ 三条技术行为，逐条：① 在系统框架读取全部复制内容，仅本地匹配 http/https 链接，不记录不上传；② 在音乐应用内旁路只读读取网络响应以嗅探歌词，并改写蓝牙连接状态查询以触发歌词上报，不连接真实设备；③ 上述行为均可由本应用对应开关完全停止。卡末接一小节「Root 用途」（沿用 `ui_dev_root_body`：仅供日志报告读取 LSPosed 日志，只读不改系统） |
| 3 | 赞助作者（独立卡） | 标题行 + 说明 + 支付宝二维码（**默认收起**，点标题行展开，箭头 0°↔90°） |
| 4 | 页脚（非卡） | MIT 许可一行小字 |

迁移说明：赞助卡从首页搬来（首页不再有）；`SettingsActivity` 的「创作者 / 免费公益」两行并入简介卡后删除；`AboutActivity` 整页删除。

---

## 3. 交互路径表

计步规则：底栏 tab 点击算第 1 步；从应用默认落地页（能力 tab）出发。

### 3.1 每个设置项的路径

| 设置项 | key | 路径 | 步数 |
|---|---|---|---|
| 模块总开关 | `module_enabled` | 设置 → 模块卡开关 | 2 |
| 隐藏桌面图标 | `hide_launcher_icon` | 设置 → 模块卡开关 | 2 |
| 歌词胶囊开关 | `cap_lyric_enabled` | 能力 → 歌词卡开关 | 2（卡上直切） |
| 上岛总开关 | `cap_smart_island_enabled` | 能力 → 上岛卡开关 | 2（卡上直切） |
| 链接助手 | `cap_link_enabled` | 能力 → 上岛卡 → 行开关 | 3 |
| 手机号上岛 | `cap_phone_enabled` | 同上 | 3 |
| 智能文件 | `cap_file_installer_enabled` | 同上 | 3 |
| WiFi 助手 | `cap_wifi_enabled` | 同上 | 3 |
| 蓝牙岛 | `cap_bluetooth_enabled` | 同上 | 3 |
| 任一子能力显示时长 | `linger_sec_<feature>` | 能力 → 上岛卡 → 行尾箭头（面板内拖条） | 3（面板直达） |
| 默认显示时长 | `card_linger_seconds` | 能力 → 上岛卡 → 默认时长卡拖条 | 3 |
| 默认浏览器 | `link_browser_mode` + `_package` | 能力 → 上岛卡 → 链接助手行箭头 → 「默认浏览器」行 → PickerSheet 选中 | 5 |
| 默认安装器 | `installer_mode` + `_package` | 能力 → 上岛卡 → 智能文件行箭头 → 「默认安装器」行 → PickerSheet | 5 |
| 暂存副本占用/清理 | —（动作） | 能力 → 上岛卡 → 智能文件行箭头 → 「清理暂存副本」 | 4 |
| 自动清理周期 | `apk_clean_days` | 同上 → 「自动清理」→ PickerSheet | 5 |
| 开发者日志 | `dev_log_enabled` | 设置 → 开发者选项 → 日志开关 | 3 |
| 权限中心 | — | 设置 → 运行状态卡 | 2 |
| 启动 LSPosed | — | 设置 → 工具卡 | 2 |
| 赞助二维码 | — | 关于 → 赞助卡标题行 | 2 |
| 宿主适配说明 | — | 能力 → 歌词卡 → 宿主行 | 3（抽屉直达） |

> 上面的 5 步项（默认浏览器/安装器/清理周期）是「低频深层偏好」。若需压到 3 步，唯一做法是把面板内容提到行首页——会破坏「行 = 开关」的一致性，**建议维持 5 步**并在面板首行给出当前值摘要（一眼可见，不必进入即可知道设成了什么）。

### 3.2 可从「卡片/行 → 就地展开面板」直达的入口

| 入口 | 面板内容 | 直达收益 |
|---|---|---|
| 上岛子能力页 5 行的行尾箭头 | 该功能显示时长 + 恢复默认（link/file 另含专属项） | 改时长从「跳页 → 找卡 → 拖条」变为同一行就地操作 |
| 智能文件行面板 | 安装器 / 清理副本 / 清理周期 三项集中 | 与「智能文件」心智绑定，不再散落在通用「默认应用」卡 |
| 歌词宿主行 | 该宿主来源说明抽屉 | 长清单不进正文，点谁看谁 |

不出现在面板里的：总开关、默认时长（兜底语义需独立卡片才不混淆）、说明小字。

---

## 4. 需要新增 / 合并 / 删除的偏好项

### 4.1 合并（无需新 key）

- **per-ability 开关集中管理**：`cap_*` 已是集中管理（6 个 key 同前缀），**不需要**新增「每能力独立开关」的总控 key。缺的只是 UI 组织与一个代码内注册表。
- 建议新增**代码内注册表**（非偏好、无持久化）：`CapabilityRegistry`，字段 `id / title / subtitle / iconRes / switchKey / lingerFeature / children`。用途：三 tab 渲染、通配联动白名单、验收自检（防止 UI 与 key 漂移）。这是实现产物，本文档只定 schema。
- `cap_link_enabled` 与 `cap_smart_island_enabled` 语义重叠：**冻结 `cap_link_enabled`**（只读回落，UI 不再写），不删 key（删除会让老用户「关过链接」的设置静默失效）。

### 4.2 新增（**均需用户拍板，当前代码中不存在**）

| 编号 | 拟新增 key | 类型/默认 | 用途 | 影响面 |
|---|---|---|---|---|
| N1 | `cap_lyric_linger_seconds` | Int / 30 | 歌词胶囊显示时长 | 需宿主（星流）支持时长参数才有效；若星流不提供该参数，此键**无实际作用**，属假开关 |
| N2 | `lyric_host_disabled` | String（逗号分隔包名）/ "" | 逐宿主停用 | 需在 12 处 `module_enabled && cap_lyric_enabled` 闸门旁增加宿主判定；17 个宿主包名（+ MeloYou 兜底 = 18 个）逐一接线 |

> N1/N2 都不在现状代码里。**不拍板就不做**，UI 保持只读清单（§2.4）。见 §7。

### 4.3 删除（只删 UI，不删 key）

| 删除对象 | 理由 | 兼容处理 |
|---|---|---|
| 首页 4 行万能入口（权限中心/设置/关于/开发者） | 与三段底栏重复 | 三 tab 承载 |
| 首页赞助卡 | 与能力无关，用户要求归入关于 | 移到关于 tab |
| `AboutActivity` 整页 + manifest 条目 | 内容并入关于 tab | 无外部入口（`exported=false`） |
| `SettingsActivity` 的「创作者 / 免费公益」两行 | 与关于 tab 重复 | 并入简介卡 |
| `SettingsActivity` 的「关于」语义 | 已拆到关于 tab | — |
| 文案 `cap_lyric_on`「正在推送歌词」、`lyric_status_ready`「已生效」 | 无法验证的断言 | 用 §2.4 枚举替换（改 strings，属实现） |

保留但改造：`SettingsActivity`（若确认无外部显式 Intent 调用可后续删，当前 `exported=true` 保守保留并内部转 tab）。

### 4.4 必须修的既有缺陷（不算新增偏好）

`LinkDetailActivity.kt:94-100` 反射扫 `cap_` 前缀 → 改显式 5 项白名单（`cap_link_enabled` / `cap_phone_enabled` / `cap_file_installer_enabled` / `cap_wifi_enabled` / `cap_bluetooth_enabled`），**不得包含 `cap_lyric_enabled`**。

---

## 5. 迁移映射表

| 旧页面 / 入口 | 新归属 | Activity 处置 | 兼容策略 |
|---|---|---|---|
| `HomeActivity`（alias 目标 + `MODULE_SETTINGS`） | 三 tab 壳（能力/设置/关于） | **保留文件名与类名，就地改造**为 tab 容器 | `LauncherAlias.targetActivity` 不变 → 隐藏图标逻辑（`setLauncherIconHidden` 用 `ui.LauncherAlias`）零改动；`MODULE_SETTINGS` filter 不变 → LSPosed 模块设置按钮仍可用 |
| `SettingsActivity` | 设置 tab | 保留（`exported=true` 不动），内部 `startActivity(HomeActivity#settings)` 后 `finish()` | 若有外部显式 Intent（如星流拉起）仍可进入 |
| `AboutActivity` | 关于 tab | **删除** + 删 manifest 条目 | 无外部入口 |
| `DevActivity` | 设置 tab → 工具卡 | 保留为二级页 | `parentActivityName` 改指向 `HomeActivity`（已是） |
| `PermissionCenterActivity` | 设置 tab → 运行状态卡 | 保留为二级页 | 同上 |
| `LyricDetailActivity` | 能力 → 歌词卡 | 保留，重排为 §2.4 | `parentActivityName` 不变 |
| `LinkDetailActivity` | 能力 → 上岛卡 | 保留，重排为 §2.5 | 同上 |
| `SheetActivity` / `PickerSheet` | 全局 | 保留 | 说明与选择器唯一出口 |
| `IslandColdStartActivity` / `IslandService` / 两个 receiver | 无 UI 链路 | 不动 | — |

tab 状态传递：`HomeActivity` 用 `Intent` extra（如 `EXTRA_TAB = "settings" | "about" | "capability"`，默认 `capability`）在 `onCreate` 落地；`onNewIntent` 同步处理，避免二次打开不切换。

---

## 6. 逐条验收标准

### 6.1 全局

- [ ] A1 三段底栏固定顺序「能力 / 设置 / 关于」，默认落地「能力」；任意 tab 切换不重建页面（进度条/滚动位置不重置）。
- [ ] A2 `module_enabled=false` 时：能力 tab 顶部出现停用横幅；两主卡摘要均为「模块已停用」；卡仍可点进详情（开关可操作，但摘要保持停用态）。
- [ ] A3 卡片材质只用 `ui.glassCard()`，行只用 `ui.row()`；不新增 XML 布局，不引入新依赖。
- [ ] A4 任一文案不得断言代码无法判定的状态。**黑名单**：「正在推送」「已生效」「已连接音乐应用」「作用域已勾选」。

### 6.2 能力 tab

- [ ] B1 每张主能力卡必显 5 项：图标徽章 / 标题 / 一行状态摘要 / 开关 /「点卡片进入设置 ›」。缺一项即不通过。
- [ ] B2 歌词卡摘要只允许 §2.2.1 的 3 种枚举；上岛卡摘要只允许 §2.2.2 的 5 种枚举（按优先级短路）。
- [ ] B3 上岛卡点条：5 点固定顺序 link/phone/file/wifi/bt，开=ok 色、关=off 色，随开关即时更新（无需重进页面）。
- [ ] B4 卡片关闭态 `alpha=0.55` 且仍可点；开关点击不触发进详情（现有 `GlassSwitch` 已消费触摸，需回归验证）。
- [ ] B5 「更多能力」占位卡：`alpha=0.5`、不可点、文案「后续更新会在这里出现」；不显示假入口。
- [ ] B6 首页（能力 tab）**不出现**：赞助二维码、开发者入口、关于长文、权限中心长文。

### 6.3 歌词子能力页

- [ ] C1 状态卡三态文案与 §2.4 一致；开关直写 `cap_lyric_enabled`，关态整页仍可读。
- [ ] C2 宿主清单三组齐全（9 + 8 + 1），显示名与包名与 §2.4 表格逐字一致。
- [ ] C3 每行右侧只允许「已安装 / 未安装」两态（**禁止**任何作用域相关状态或开关）。
- [ ] C4 MeloYou 行必须带默认作用域警示小字，且不得出现在其余分组。
- [ ] C5 点击宿主行 → 抽屉，标题为宿主名，正文含「需在 LSPosed 勾选 <包名> 并强制停止该应用」。
- [ ] C6 空态：某宿主未安装 → 该行状态点 off + 「未安装」，行仍可点（可读说明），不置灰整行导致不可读。
- [ ] C7 错误态：`module_enabled=false` → 状态卡显示「模块已停用，歌词不会推送」，开关仍可切换。

### 6.4 上岛子能力页

- [ ] D1 总开关卡文案含「开：5 个子功能一并开；关：一并关」；切换后 5 个子开关即时全开/全关，`recreate()` 后状态一致。
- [ ] D2 **回归阻断项**：关闭上岛总开关后，`cap_lyric_enabled` 必须保持原值不变（§4.4 缺陷已修）。
- [ ] D3 5 行顺序固定（链接助手 → 手机号 → 智能文件 → WiFi → 蓝牙），每行含标题 + 一行说明 + 开关 + 48dp 箭头。
- [ ] D4 箭头独立命中：点箭头只展开/收起（箭头 90°↔270°，160ms），不改变开关；点行内其他位置只切开关，不展开。
- [ ] D5 面板首行为「显示时长」：当前值文案为「当前：N 秒」或「当前：默认（跟随总设置）」或「当前：常驻（不自动收起）」；SeekBar 0..120；「恢复默认」仅在 `linger_sec_<feature> >= 0` 时可用，否则 `alpha=0.4`。
- [ ] D6 默认时长卡位于 5 行之后，文案「未单独设置的功能都用这个时长；拖到最左表示常驻」；拖到最左写 0，HUD 显示「常驻」。
- [ ] D7 浏览器/安装器选择：PickerSheet 首项恒为「跟随系统默认浏览器 / 跟随系统默认」，其余为 `BrowserChoice.installed(context)` / `InstallerChoice.installed(context)`；选中固定项时同时写 `mode=pinned` 与包名；选「跟随系统」时只写 `mode=system`。
- [ ] D8 空态：`BrowserChoice.installed(context)` 为空 → 面板内该行副标题显示「未找到可选择的浏览器」，PickerSheet 仅 1 项且选中不报错；安装器同款。
- [ ] D9 智能文件面板：清理暂存副本显示「暂无」或「N 个 · x.x MB」；清理后 Toast「已清理 N 个暂存副本」并刷新为「暂无」。
- [ ] D10 星流状态行五态文案与 `LinkHub.stateName()` 映射一致：READY=已连接、NOT_INSTALLED=未安装星流、WAITING=未就绪、REJECTED=被星流拒绝、其他=原样英文；点击进权限中心。
- [ ] D11 三段小字（作用域/隐私/清后台）保留原文，不删不改。

### 6.5 设置 tab

- [ ] E1 模块卡两行开关整行可点，且开关与整行点击不会重复触发一次写两次值。
- [ ] E2 隐藏图标开关：开 → alias 禁用 + 常驻最小通知出现；关 → alias 启用 + 通知消失（回归 `BaseActivity.setLauncherIconHidden`）。
- [ ] E3 运行状态卡三行摘要取自真实检测：模块加载（读 `xinghe_dev.log` 尾 16KB 含 "IslandService started"）、Root（`su -c true` 异步，不阻塞主线程）、星流（`LinkHub.isReady()`）；卡整体可点 → 权限中心。
- [ ] E4 开发者选项行右侧摘要为「日志已开 / 日志已关」，进入后可切 `dev_log_enabled`、看状态、分享报告、清除（功能不回退）。
- [ ] E5 设置 tab 不出现任何 `cap_*` 功能开关。

### 6.6 关于 tab

- [ ] F1 三张卡固定顺序：项目简介 → 如实说明 → 赞助作者。
- [ ] F2 如实说明卡必须包含 3 条技术行为 + Root 用途小节，逐条列出（不得压缩成一段）。
- [ ] F3 赞助二维码默认 `GONE`，点标题行展开，箭头 0°↔90°，仅此处可展开。
- [ ] F4 关于 tab 不出现功能开关与调试入口。

### 6.7 迁移与兼容

- [ ] G1 桌面图标（隐藏/显示）与 LSPosed 模块设置按钮在改造后仍可用。
- [ ] G2 `SettingsActivity` 被显式 Intent 拉起时落到设置 tab，不出现空白页。
- [ ] G3 `AboutActivity` 删除后无残留 manifest 引用、无残留 `R.string`/`R.drawable` 死引用（跑 `docs/check_refs.py`）。
- [ ] G4 三段 tab 的页面滚动位置在 tab 间切换后保留。

---

## 7. 需要用户拍板

1. **歌词胶囊要不要「显示时长」？** 现状代码里没有歌词专属时长 key（`card_linger_seconds` / `linger_sec_*` 只作用于上岛卡，见 `IslandService.kt:56`）。若星流宿主不接收时长参数，新增 `cap_lyric_linger_seconds` 会变成假开关。选项：(a) 歌词页不放时长（本档默认）；(b) 新增 N1，并先确认星流侧支持。
2. **要不要逐宿主开关？** 现状 17 个宿主只能靠 LSPosed 作用域整体启停，UI 侧无法单独关掉「网易云」而保留「酷狗」。选项：(a) 维持只读清单（本档默认，零风险）；(b) 新增 N2（`lyric_host_disabled`），需在 12 处闸门旁加判定并逐包接线。

---

### 附：本文档引用的源码位置

- 偏好键：`settings/ModulePrefs.kt:17-49`、`:116`、`:122`、`:130`
- 通配联动缺陷：`ui/page/LinkDetailActivity.kt:94-100`
- 首页卡状态矛盾：`ui/page/HomeActivity.kt:50-58`
- 子能力闸门：`capability/link/LinkHub.kt:161-169`
- 时长生效点：`app/IslandService.kt:56`、`capability/link/LinkHub.kt:226`
- 宿主清单：`lyric/Constants.kt:107-108`、`:112-124`、`:125-196`
- 作用域：`res/values/arrays.xml:14-49`
- 卡片与原子：`ui/widget/CapabilityCard.kt`、`ui/widget/UIKit.kt`
