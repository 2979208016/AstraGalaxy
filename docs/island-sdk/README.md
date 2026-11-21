> 本目录为星穹接入的上岛 SDK 官方规范，属于项目内资料，不可清理。
>
> 说明：0.1.2 aar 实际接入位置为 `app/libs/astraisland-sdk-0.1.2.aar`；
> sample 工程与 zip 不随仓库分发，存放于 `工作/资料/文档/island-sdk-sample/`。
> 下方为官方 README 原文，未改动。

---

# 星河岛（AstraIsland）接入规范库 · SDK 0.1.2

面向 Android 应用的星河岛接入文档库，只描述 **SDK 0.1.2 + 通信版本 7** 这一套接口。整理自星流 AstraFlow 星河岛开发者平台 <https://astraflow.cc/island/>，并以 0.1.2 工具包字节码交叉核对（2026-11-02）。

## 一、文件索引

每个主题只在下列一处详写，其余文件只做指向。

| 文件 | 唯一主题 | 适读场景 |
| --- | --- | --- |
| [`01-概述与环境.md`](01-概述与环境.md) | 平台定位、三种形态、九套模板、环境与依赖、下载资源、许可、不开放能力 | 评估要不要接、准备工程 |
| [`02-接入与清单.md`](02-接入与清单.md) | 接入三步、清单自动合并、发送权限、`<queries>`、宿主与身份校验、自检清单 | 第一次接入、装不上/连不上 |
| [`03-通信协议.md`](03-通信协议.md) | SDK 内部协议：广播、Binder、Bundle 线格式键名、结果码与优先级整数 | 排障、原理核对（**不要直接调用**） |
| [`04-SDK接口参考.md`](04-SDK接口参考.md) | 公开 API：`IslandClient`、回调、`IslandActivity`、`Capsule`、图片、按钮、`ButtonLayout` | 写代码时查签名 |
| [`05-卡片数据结构.md`](05-卡片数据结构.md) | 九套卡片的字段、默认值、必填与互斥、按钮回调约定 | 挑模板、填字段 |
| [`06-生命周期与配额.md`](06-生命周期与配额.md) | 连接状态机、内容生命周期、**数值上限总表**、排位与显示规则 | 内容不显示、被限流 |
| [`07-错误码与排查.md`](07-错误码与排查.md) | `IslandResult` 语义、连接状态排查、常见问题决策树 | 拿到非 `OK` 结果 |
| [`08-示例代码.md`](08-示例代码.md) | 最小示例与九类场景示例、Java 调用要点 | 直接抄 |
| [`09-版本变更记录.md`](09-版本变更记录.md) | 0.1.0 / 0.1.1 / 0.1.2 版本表与升级要点 | 升级、确认能力边界 |
| [`10-项目对接备忘.md`](10-项目对接备忘.md) | 星穹专属：LSPosed 进程模型、出卡链路、编号约定（**非平台规定**） | 维护星穹的对接代码 |

## 二、来源与可信度标记

| 标记 | 含义 |
| --- | --- |
| `[站点]` | 直接来自 `astraflow.cc/island` 页面原文，随文附页面名 |
| `[SDK]` | 站点未公开，来自 `astraisland-sdk-0.1.2.aar` 的字节码（`javap`）核实 |
| `[项目]` | 星穹（AstraFirmament）自身的对接约定，不是平台规定，集中放在 10 |
| `[未确认]` | 站点与 SDK 都没有，**不臆造**，需向平台方确认 |

规则：凡是标 `[未确认]` 的地方，不得用推测填充；凡是标 `[SDK]` 的地方，只用于排障与原理核对，接入代码只调用公开 API。

## 三、站点页面（2026-11-02 抓取，全部 HTTP 200）

站点是纯静态站，正文可直接抓取，无 SPA 渲染问题。

产品介绍：

- <https://astraflow.cc/island/>、<https://astraflow.cc/island/index.html>
- <https://astraflow.cc/island/product-overview.html>、`product-content.html`、`product-system.html`、`product-devices.html`

设计规范：

- <https://astraflow.cc/island/design-principles.html>、`design-capsule.html`、`design-card.html`、`design-templates.html`
- `design-buttons.html`、`design-type-color.html`、`design-display.html`、`design-motion.html`

开发接入：

- <https://astraflow.cc/island/develop-overview.html>、`develop-quickstart.html`、`develop-activity.html`
- `develop-templates.html`、`develop-interaction.html`、`develop-limits.html`、`develop-api.html`
- `develop-faq.html`、`develop-changelog.html`

下载：

- <https://astraflow.cc/island/download.html>
- <https://astraflow.cc/island/download/astraisland-sdk-0.1.2.aar>
- <https://astraflow.cc/island/download/astraisland-sdk-sample-0.1.2.zip>
- <https://astraflow.cc/island/download/SHA256SUMS>、`LICENSE.txt`

> `design-buttons`、`design-card`、`design-type-color`、`design-motion` 不在顶部导航里，由正文交叉链接发现。

## 四、本目录的本地文件

| 文件 | 说明 |
| --- | --- |
| `astraisland-sdk-0.1.2.aar` | 站点发布的 0.1.2 工具包原件，169,504 字节 |
| `astraisland-sdk-sample-0.1.2.zip` | 站点发布的 0.1.2 示例工程压缩包，231,334 字节 |
| `astraisland-sdk-sample-0.1.2/` | 上面压缩包解出的工程，可直接用 Android Studio 打开；`app/libs/` 内已放同一份 AAR |
| `SHA256SUMS` | 站点发布的校验值，见下表 |
| `LICENSE.txt` | PolyForm Noncommercial License 1.0.0 全文 |

校验值（`sha256sum` 实测与 `SHA256SUMS` 一致）：

| 文件 | SHA256 |
| --- | --- |
| `astraisland-sdk-0.1.2.aar` | `1e7f71caad0097e5be908420183eaf7826ae364b9bf64d4a436977833af01f14` |
| `astraisland-sdk-sample-0.1.2.zip` | `6e0654fac1bfda1e4681d1db70a002fffab5135c8cc12fa7635d563075b17cd5` |

> 本目录只保留 0.1.2 一套资料。历史版本（0.1.0 / 0.1.1）的工具包与示例工程不在此留存，版本差异见 [`09-版本变更记录.md`](09-版本变更记录.md)；需要旧包时从站点 `download/` 目录按旧文件名取。

## 五、建议阅读顺序

1. 第一次接入：01 → 02 → 04 → 05 → 08。
2. 内容不显示或返回非 `OK`：07 → 06 → 03（仅在需要看宿主日志整数码时）。
3. 维护星穹对接代码：10 → 04 → 06。

## 六、两条容易踩的前提

- 传内容用的是**公开 API**（`com.astraisland.sdk`），03 的线格式只用于读宿主日志、核对行为，不作为接入手段。
- 版本纪律：接入库与星流必须同为**通信版本 7**，高于或低于 7 都不建立连接（版本表见 [`01-概述与环境.md`](01-概述与环境.md) §1.4，「版本号 ≠ 通信版本」见 [`09-版本变更记录.md`](09-版本变更记录.md) §9.6）。
