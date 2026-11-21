# 6.1 界面重写（Cupertino / 液态玻璃）

> **9.0 起本文件描述的旧 hig 体系已全部移除**（2026-11-15）：`zone.ien.hig` / `hig-icons-extended` /
> `io.github.kyant0:backdrop` / `io.github.kyant0:shapes` / `zone.ien.capsule` 五个依赖删除，
> `ui/cupertino` 与 `ui/coui` 两层自绘控件删除。当前界面架构见文末「9.x 现状」一节；
> 下文各节仅作为 6.x–8.0 的历史决策记录保留（当时为什么这么写）。


本文件记录 6.0 之后这一轮界面重写的架构与迁移映射。**不要在旧 View 界面上继续加东西**。

## 为什么重写

6.0 的 View 壳子是自造样式：底栏是自绘圆角矩形（有硬边、无背景取样），三套「风格」只是换色 token，
和三份参考项目的真实观感无关。本轮改为**直接使用参考项目的上游部件**：

- [ChizuruMizuhara](https://github.com/JiangNanOvO/ChizuruMizuhara)（本项目分支，已做 iOS / 液态玻璃美学）
- 其依赖：`zone.ien.hig`（Cupertino 部件：底栏、分组列表、开关、弹层）、
  `io.github.kyant0:backdrop`（背景取样 + 模糊/折射）、`io.github.kyant0:shapes`、`zone.ien.capsule`
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（backdrop 的原理与效果来源）
- [Eta](https://github.com/Mangi-11/Eta)（莫奈取色与模糊分档）、
  [rikkahub](https://github.com/rikkahub/rikkahub)（原生动效节奏）、
  [ImageToolbox](https://github.com/T8RIN/ImageToolbox)（自定义广度）

## 层级

```
ui/cupertino/          从 Chizuru 移植的部件层（主题、玻璃、底栏、分组列表、开关、弹层、图标）
ui/shell/ChizuruApp    三页外壳（能力 / 设置 / 关于）+ 背景取样 + 玻璃底栏
ui/page/*             每个页面一个 @Composable，读写仍然走 ModulePrefs
```

旧 `ui/widget/{Glass,GlassSwitch,UIKit,Motion,CapabilityCard}.kt` 与 `ui/page/*Activity`（View 版）
在本轮结束后删除，不再作为入口。

## 迁移映射

| 旧入口（View） | 新入口（Compose） |
|---|---|
| `ui/shell/MainActivity`（自绘底栏） | `ui/shell/ChizuruApp` + hig `CupertinoNavigationBar`（带 backdrop 玻璃与按住拖动换页） |
| `ui/page/LinkDetailActivity` 470 行混合设置 | 能力页内联面板：每个子能力行展开就地设置（时长 / 清理 / 自动清理 / 浏览器 / 安装器） |
| `ui/page/LyricDetailActivity` | 能力页歌词卡展开后的内联面板 |
| `ui/page/PermissionCenterActivity` | 设置页「运行状态」子页（Compose） |
| `ui/page/DevActivity` | 设置页「工具」子页（Compose） |
| `ui/page/AboutActivity` | 关于页（Compose） |
| `ui/shell/{BottomBar,PageScaffold,CapabilityPage,SettingsPage,AboutPage,IslandPanels}.kt` | 全部由 Compose 版本取代 |

## 交互原则（用户明确要求）

1. **一个入口**：能力卡上的子能力行只有一种交互 —— 行尾箭头展开/收起就地设置。不再有「点标题行进独立页」。
2. 不再出现无意义的装饰（例如主能力卡上那排绿点）。
3. 图标与语义必须对应（开发者选项用终端图标，不能复用音符）。
4. 三套风格是**真实部件差异**，不是换色：
   - iOS 液态玻璃：悬浮胶囊底栏 + 背景取样模糊 + 折射高光 + 按住拖动换标签（上游 `CupertinoNavigationBar` 自带）
   - 澎湃：全宽实心底栏、大圆角、无模糊、按压缩放
   - 莫奈：壁纸取色、无模糊、Material 动效曲线
5. 文案精简：说明集中到关于页，设置项只留一句必要说明。


---

## 9.x 现状（COUI 纯组件化，2026-11-15 起）

**组件库**：`io.github.itoseo:coui:0.0.1-alpha11` + `coui-icons:0.0.1-alpha11`（ColorOS 凝光组件），
深浅色跟随系统。`hig`/`backdrop`/`shapes`/`capsule` 依赖与 `ui/cupertino`、`ui/coui` 目录已删。

**页面骨架**（`ui/shell/AppScaffold.kt`）：页面只走两个入口——
- `AppTabPage(title, subtitle, bottomClearance)`：一级标签页，COUI `Scaffold` + `LargeTopAppBar`；
- `AppSubPage(title, onBack, bottomClearance, actions)`：子页，COUI `TopAppBar` + 返回按钮（API 34+ `TopAppBarButton`，31-33 回退 clickable）。

**行 DSL**（`ui/shell/AppRows.kt`）：`group { ... }` 包 COUI `Group`；
组内行组件 `SwitchRow / LinkRow / ValueRow / ActionRow / SliderRow / PickerRow / NoteRow`，
全部落在 COUI `Item`/`Switch`/`Slider`/`Dialog` 上。滑条拖动期间由 `PagerGesture.locked`
（`awaitEachGesture` 按下即锁、抬手解锁）锁住标签页横向翻页。

**导航外壳**（`ui/shell/AstraApp.kt`）：三标签 `HorizontalPager` + 底栏——
API 34+ 用 COUI `NavigationBar`（`CouiFloatingRenderer`，选中色显式指定 `accent`/未选中
`onSurfaceVariant`，解决默认 230/184 透明度对比过淡）；31-33 用纯色回退底栏。
二级/三级页为覆盖式推入页：`CapabilityPage` 内部 `PredictivePageStack` 固定三层槽位；
设置页的权限中心/开发者选项、关于页的「关于项目」文档页走 `AstraApp` 的 overlay
（`"perm" / "dev" / "about"` → `PredictivePageStack`）。

**预测性返回**（`ui/shell/PredictivePageStack.kt`）：槽位固定三层纯平移跟手退栈；
9.1 起手势提交后顶层先顺势滑出屏幕（≈200ms）再退栈，修掉「回跳全屏闪一帧」的闪回。

**图标**：控件/功能图标统一 `CouiIcons`（coui-icons 模块）；仅库内确无对应语义
（Info / Record / Mail / Move / TabSystem / Warning / Sparkle）保留 `ui/icons/` 自绘。
行图标统一 `ColorFilter.tint(accent)` 上色（自绘图是黑色填充，不上色深底下全黑）。

**文案**：长说明（读取了什么 / Root 用途 / 日志策略 / 前提 / 免责声明）集中在
`AboutProjectPage.kt` 文档页（可复制全文）；功能页只留一句话说明。

**开发者日志**（`capability/link/DevLog.kt`）：内存环形缓冲 3000 条；日志包
`xinghe_report.zip` 由后台节流线程按「打包周期」（默认 5 分钟，开发者选项可调 1–720 分钟）
常驻重建、原子替换；「清理周期」（默认 168 小时，可调 1–720 小时）到期清空缓冲并删包。
进开发者选项不再现场打包。
