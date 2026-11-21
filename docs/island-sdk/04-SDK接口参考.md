# 04 · SDK 接口参考

> 来源：`[站点]` <https://astraflow.cc/island/develop-api.html>（SDK **0.1.2** 全部公开接口，包 `com.astraisland.sdk`）；签名与默认值经 `[SDK]` `astraisland-sdk-0.1.2.aar` 字节码核对。
>
> 签名以 Kotlin 书写；Java 中属性对应 `getXxx()` / `isXxx()`，带默认值的参数提供多个重载，工厂方法为静态方法（见 [`08-示例代码.md`](08-示例代码.md) §8.10）。

## 4.1 包结构

| 包 | 可见性 | 内容 |
| --- | --- | --- |
| `com.astraisland.sdk` | **公开**，应用只用这个包 | `IslandClient`、`IslandActivity`、`Capsule`、`IslandCard` 及九个子类、按钮、`ButtonLayout`、回调、结果枚举 |
| `com.astraisland.protocol` | 内部实现 | `IslandProtocol`、`IslandWire`、`IIslandClient`、`IIslandSession`（见 [`03-通信协议.md`](03-通信协议.md)） |

## 4.2 `IslandClient`

```kotlin
class IslandClient(context: Context)
```

`context` 可为任意上下文，客户端只保留其**应用上下文**（不会泄漏 Activity）。

| 成员 | 说明 |
| --- | --- |
| `enum class State { NOT_INSTALLED, WAITING, REJECTED, READY }` | 连接状态 |
| `val state: State` | 当前状态。**创建后为 `WAITING`** |
| `val isReady: Boolean` | 是否已连接、可以显示内容 |
| `fun setCallback(callback: IslandCallback?)` | 设置回调；传 `null` 不再接收回调 |
| `fun isHostInstalled(): Boolean` | 设备上是否安装了可用星流（通信版本 7） |
| `fun connect()` | 连接星河岛。未安装可用星流 → `NOT_INSTALLED`；否则 `WAITING`，宿主响应后 `READY`。**已连接时再次调用会重新连接** |
| `fun disconnect()` | 断开连接，**并结束本应用在星河岛上的全部内容** |
| `fun start(activity: IslandActivity): IslandResult` | 显示一条内容；同编号已存在时**整份替换** |
| `fun update(activity: IslandActivity): IslandResult` | 与 `start` 完全相同 |
| `fun end(id: String, outro: Outro? = null): IslandResult` | 结束一条内容，可带收尾 |
| `fun endAll(): IslandResult` | 结束本应用全部内容 |
| `fun listMine(): List<String>` | 本应用仍在岛上的内容编号（含暂未显示、排队中的） |
| `const val SDK_VERSION: String = "0.1.2"` | 伴生常量 |
| `const val PROTOCOL_VERSION: Int = 7` | 伴生常量 |

`start` / `update` / `end` / `endAll` / `listMine` **会阻塞到宿主处理完毕**，须在后台线程调用（见 §4.10）。

### 状态语义

| `State` | 含义 | 建议处理 |
| --- | --- | --- |
| `NOT_INSTALLED` | 未安装星流，或已安装的星流不支持通信版本 7 | 不显示星河岛相关功能入口，应用照常运行 |
| `WAITING` | 等待星河岛响应。连接后长时间停留，说明星河岛未启用或设备未就绪（OPPO / 一加 / realme 还可能是未启用插件「流体云事件接入」） | 无需重试，等待即可 |
| `REJECTED` | 星河岛拒绝了连接（例如身份核对未通过） | 查应用进程与清单声明合并（[`02-接入与清单.md`](02-接入与清单.md) §2.3） |
| `READY` | 已连接，可以显示内容 | — |

> 宿主重启后客户端**自动重连**，状态依次变为 `WAITING`、`READY`；此时应调 `listMine()` 核对并在需要时重新提交（[`06-生命周期与配额.md`](06-生命周期与配额.md) §6.2）。

## 4.3 `IslandCallback`

```kotlin
abstract class IslandCallback
```

按需重写，所有方法默认空实现，**都在主线程调用**。事件尽力送达，**不作为业务依据**。

| 方法 | 触发时机 |
| --- | --- |
| `open fun onStateChanged(state: IslandClient.State)` | 连接状态变化 |
| `open fun onAction(activityId: String, actionId: String)` | 用户点了卡片上的按钮。`actionId` 为应用设置的按钮编号，或 `TimerControl.actionId`、`MediaControl.actionId`、`StatusCard.ACTION_STOP` |
| `open fun onReply(activityId: String, text: String)` | 用户在消息卡片回复框发送了文字。星河岛交出文字后提示「已交给应用发送」；**实际发送由应用负责** |
| `open fun onSeek(activityId: String, positionMs: Long)` | 用户在音乐卡片拖动进度条并松手，`positionMs` 为毫秒 |
| `open fun onDismissed(activityId: String)` | 用户在胶囊上划动收起了该内容。**内容并未结束**，满足条件会重新显示（[`06-生命周期与配额.md`](06-生命周期与配额.md) §6.5） |
| `open fun onExpanded(activityId: String)` | 用户展开了卡片 |
| `open fun onCollapsed(activityId: String)` | 卡片收起 |
| `open fun onEnded(activityId: String, reason: EndReason)` | 内容被星河岛结束 |

```kotlin
enum class EndReason { EXPIRED, REMOVED }
```

| 取值 | 含义 |
| --- | --- |
| `EXPIRED` | 到达自动消失时间，或显示满 8 小时 |
| `REMOVED` | 用户在星流中关闭了本应用的内容或音乐内容，或星河岛停止运行 |

> 用户开启「长时间未操作时收起」或「停留后收起」导致自动收起时，**不调用** `onDismissed`。

## 4.4 `IslandResult`

```kotlin
enum class IslandResult {
    OK, IMAGE_REJECTED, NO_PERMISSION, SOURCE_DISABLED, KIND_DISABLED,
    QUOTA_EXCEEDED, RATE_LIMITED, INVALID, BUSY, NOT_CONNECTED
}
```

- `val accepted: Boolean`：是否已受理（`OK` 或 `IMAGE_REJECTED`）。
- **受理 ≠ 已显示**：应用在前台、排位靠后、被用户收起时都可能「受理但不显示」。

各取值的含义、典型原因与处置见 [`07-错误码与排查.md`](07-错误码与排查.md) §7.1；宿主日志中的整数码映射见 [`03-通信协议.md`](03-通信协议.md) §3.5。

## 4.5 `IslandActivity`

```kotlin
class IslandActivity                                    // 由 Builder 生成
class IslandActivity.Builder(id: String, capsule: Capsule, card: IslandCard)
```

一条内容。`id` 为内容编号，**本应用内唯一**，1~128 字符（不能为空白）。

| Builder 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `setPriority(priority: Priority)` | `DEFAULT` | 排位参考。**消息卡片不使用此项**，按新消息排位 |
| `setAlertOnStart(alert: Boolean)` | `false` | 首次显示时自动展开片刻。同一应用 10 秒内最多一次 |
| `setAlertOnUpdate(alert: Boolean)` | `false` | 更新且内容有变化时自动展开片刻。同一应用 10 秒内最多一次；连续更新显示最新内容，期间提出的展开要求保留一次 |
| `setLockScreenVisibility(visibility: LockScreenVisibility)` | `PUBLIC` | 锁屏时显示多少（见下） |
| `setOpenIntent(intent: PendingIntent?)` | `null` | 点击卡片时执行的操作 |
| `setOwnNotification(id: Int, tag: String? = null)` | 未设置 | 声明这条内容对应本应用哪条通知（`NotificationManager.notify` 的编号与标签） |
| `setPostedAt(timeMillis: Long)` | 未设置 | 发送时间（`System.currentTimeMillis()` 口径，> 0）。来源栏右侧显示「刚刚」「5 分钟前」或具体时刻；消息卡片以此区分新消息 |
| `setStaleAt(timeMillis: Long)` | 未设置 | 过时时间（> 0）。过后卡片注明「信息可能已过时」，内容仍保留 |
| `setDismissPolicy(policy: DismissPolicy)` | `untilEnded()` | 消失方式 |
| `setLandscapeText(text: String?)` | `null` | 横屏胶囊的完整短文字，≤32 字符；数字与英文按组竖排后须在四行以内 |
| `setAccentColor(color: Int?)` | `null` | 强调色（ARGB），用于进度条、主要按钮、放大数值等；**过暗会被提亮** |
| `setContentDescription(text: String?)` | `null` | 无障碍读屏说明，≤256 字符 |
| `build(): IslandActivity` | — | 生成内容 |

### 构造期异常（`IllegalArgumentException`）

| 触发条件 |
| --- |
| `Builder(...)`：编号为空白或超过 128 字符 |
| `setPostedAt` / `setStaleAt`：时间 ≤ 0 |
| `setLandscapeText` / `setContentDescription`：文字空白或超长 |
| `build()`：全部文字合计超过 8192 字节（UTF-8） |

### 只读属性

`id`、`capsule`、`card`、`priority`、`alertOnStart`、`alertOnUpdate`、`lockScreenVisibility`、`openIntent`、`notificationId: Int?`、`notificationTag: String?`、`postedAtMillis: Long?`、`staleAtMillis: Long?`、`dismissPolicy`、`landscapeText`、`accentColor`、`contentDescription`。

### `Priority`

```kotlin
enum class Priority { LOW, DEFAULT, HIGH }
```

| 取值 | 含义 |
| --- | --- |
| `LOW` | 低：排在正在播放的音乐之后 |
| `DEFAULT` | 默认：按卡片种类（计时、进度、音乐等）的默认排位 |
| `HIGH` | 高：外部应用内容的**最高档**，与未到点的计时同档 |

排位分档与抢占规则见 [`06-生命周期与配额.md`](06-生命周期与配额.md) §6.4。

### `LockScreenVisibility`（锁屏显示的唯一说明处，06 只引用）

```kotlin
enum class LockScreenVisibility { PUBLIC, TITLE_ONLY, HIDDEN }
```

| 取值 | 锁屏时显示 |
| --- | --- |
| `PUBLIC` | 完整显示。`requiresUnlock` 的按钮仍隐藏，回复输入框不显示 |
| `TITLE_ONLY` | 只显示标题与头像等图片：说明、正文、码、明细、比分、数值、标签、阶段名、按钮与大图均不显示 |
| `HIDDEN` | 不显示 |

涉及个人信息的内容（消息、订单详情）建议用 `TITLE_ONLY`。

### `DismissPolicy`

```kotlin
class DismissPolicy
  companion object {
    fun untilEnded(): DismissPolicy              // 一直显示到 end()；最长 8 小时
    fun afterMillis(millis: Long): DismissPolicy // 显示后经过给定时长自动消失（> 0）
  }
```

### `Outro`

```kotlin
class Outro(val success: Boolean, val text: String)
```

结束时的收尾：胶囊上显示对勾（`success = true`）或叉号加一句话，约 **1.6 秒**后消失；只在这条内容仍在岛上时显示。`text` 空白或超过 128 字符抛 `IllegalArgumentException`。

## 4.6 `Capsule`

```kotlin
class Capsule                       // 由 Capsule.Builder 生成
class Capsule.Builder(leading: IslandImage)
```

胶囊：左侧一张图片（**必填**），可跟一段小标题；右侧一项信息；最右端可再固定一个小图或进度环。

| Builder 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `setLabel(label: String?)` | `null` | 左侧图片右边的小标题，≤32 字符 |
| `setTrailing(trailing: CapsuleTrailing?)` | `null` | 右侧信息；`null` 时右侧留空 |
| `setTail(tail: CapsuleTail?)` | `null` | 固定在最右端的小图或进度环；**横屏不显示** |
| `setBreathing(breathing: Boolean)` | `false` | 左侧图标明暗呼吸（3 秒一轮），表示进行中。**照片类图片不支持** |
| `setLeadingTint(color: Int?)` | `null` | 左侧内置符号颜色（ARGB），**仅对 `IslandImage.symbol` 生效** |
| `setSatellite(satellite: Satellite?)` | `null` | 副岛内容；`null` 时显示左侧图片 |
| `build(): Capsule` | — | 照片类图片设了呼吸，或非内置符号设了颜色 → `IllegalArgumentException` |

只读属性：`leading`、`leadingTint`、`breathing`、`label`、`trailing`、`tail`、`satellite`。

### `CapsuleTrailing`（静态工厂，七种）

颜色为 ARGB，`null` 时用胶囊文字颜色或强调色；过暗会被提亮。

| 方法 | 说明 |
| --- | --- |
| `text(text: String, color: Int? = null)` | 文字，放不下时滚动，≤512 字符 |
| `number(text: String, color: Int? = null)` | 数字等宽显示，适合频繁变化的数值（「12 km」「45%」），≤512 字符 |
| `countdown(endAtMillis: Long, color: Int? = null)` | 倒计时，宿主逐秒走动，到点停在 00:00（`> 0`） |
| `stopwatch(startedAtMillis: Long, color: Int? = null)` | 正计时，宿主逐秒走动（`> 0`） |
| `progress(fraction: Float, color: Int? = null)` | 进度环，0~1 |
| `busy(color: Int? = null)` | 忙碌指示：转圈的亮弧 |
| `waveform(active: Boolean = true, color: Int? = null)` | 跳动线条；`active = false` 时静止 |

### `CapsuleTail` / `Satellite`（静态工厂）

- `CapsuleTail.image(image: IslandImage)`、`CapsuleTail.progress(fraction: Float, color: Int? = null)`
- `Satellite.image(image: IslandImage)`、`Satellite.progress(fraction: Float, image: IslandImage? = null, color: Int? = null)`（环中间可放一张图）

> 以上工厂在文字空白/超长、时间 ≤ 0、比例不在 0~1（含非有限数）时抛 `IllegalArgumentException`。胶囊的居中、宽度与副岛显示规则见 [`05-卡片数据结构.md`](05-卡片数据结构.md) §5.1。

## 4.7 `IslandImage` 与 `BuiltinSymbol`

```kotlin
class IslandImage
  companion object {
    fun picture(bitmap: Bitmap): IslandImage    // 照片类：头像、封面、商品图、截图，按原图显示
    fun glyph(bitmap: Bitmap): IslandImage      // 单色图标：按内置符号样式显示（卡片上带圆形浅色底，胶囊中不加描边）
    fun symbol(symbol: BuiltinSymbol): IslandImage
    fun appIcon(): IslandImage                  // 本应用桌面图标，仅可用于胶囊与副岛
  }
  val isAppIcon: Boolean
  val isPicture: Boolean
```

### 位图约束（不满足时 `picture` / `glyph` 抛 `IllegalArgumentException`）

| 条件 |
| --- |
| 未被回收 |
| 宽、高均在 **1~2048 像素**之间 |
| 占用内存（`Bitmap.getAllocationByteCount()`）不超过 **8 MB（8,388,608 字节）** |

### 卡片图片的特别规定（`appIcon()` 的唯一说明处）

- 卡片上的图片（左图、头像、标题小图、进度小图、对称标志）**不接受 `appIcon()`**；胶囊与副岛可以用。
- 理由：显示来源栏时来源栏已显示应用图标与名称；用户关闭「显示应用来源」且卡片无内容图片时，星河岛会在卡片左侧显示应用图标。

### `BuiltinSymbol`

| 常量 | 含义 | 常量 | 含义 |
| --- | --- | --- | --- |
| `MUSIC` | 音乐 | `PLAY` | 播放 |
| `TIMER` | 计时器 | `PAUSE` | 暂停 |
| `ALARM` | 闹钟 | `SKIP_NEXT` | 下一首 |
| `BATTERY` | 电池 | `SKIP_PREV` | 上一首 |
| `BOLT` | 闪电 | `NAVIGATION` | 方向箭头 |
| `HEADPHONES` | 耳机 | `HOURGLASS` | 沙漏 |
| `BLUETOOTH` | 蓝牙 | `INFO` | 提示 |
| `MESSAGE` | 消息 | `DOWNLOAD` | 下载 |
| `CHECK` | 对勾 | `CLOSE` | 叉号 |

## 4.8 `IslandCard`

```kotlin
sealed class IslandCard { abstract val title: String }
```

九个子类：`GenericCard`、`ProgressCard`、`EmphasisCard`、`DetailsCard`、`StatusCard`、`PictureCard`、`MediaCard`、`MessageCard`、`MirrorCard`，均通过各自的 `Builder` 生成。字段、默认值与互斥规则见 [`05-卡片数据结构.md`](05-卡片数据结构.md) §5.3~§5.11。

### 所有卡片的共同规则（`[站点]`）

- 用户开启「显示应用来源」时（星流「显示规则」页，**默认关闭**），卡片顶部由星河岛写上来源栏（应用图标与名称，名称取自系统、**应用无法修改**）；设置了发送时间时在右侧写时间。关闭后不显示来源栏，卡片左侧没有内容图片时改为显示应用图标（图片规则见 §4.7）。
- 文字长度按 **UTF-16 字符**计：标题 ≤ 128，说明 ≤ 256，正文 ≤ 4096，其余短文字 ≤ 32。必填文字不能为空白。
- 所有 `setXxx` 传 `null` 表示不显示该项。
- 文字不合规时，`Builder` 构造方法或 `setXxx` **立即抛** `IllegalArgumentException`；组合规则（按钮数量、必填项等）在 `build()` 时检查。

## 4.9 按钮

```kotlin
sealed class IslandButton(val id: String, val requiresUnlock: Boolean)
```

用户点击后 `onAction` 收到内容编号与按钮编号。`requiresUnlock = true` 时锁屏隐藏。

### 编号规则（唯一权威说明，03 §3.6 只列协议常量）

- 非空白，≤ 64 字符，同一张卡片内**不得重复**。
- 不得使用 SDK 保留编号：`pause`、`resume`、`stop`、`toggle`、`prev`、`play_pause`、`next`。
- 违反时构造方法或 `build()` 抛 `IllegalArgumentException`。

### 四种按钮

| 类 | 构造方法 | 说明 |
| --- | --- | --- |
| `TextButton` | `TextButton(id, label, style = NORMAL, icon = null, fillColor = null, requiresUnlock = false)` | 底部文字按钮，可带小图标。默认每排三个，四个时平均分两排（见本节末「ButtonLayout」）。`label` ≤ 32 字符。`fillColor` 为自定义底色（ARGB），**仅 `NORMAL` 样式可用**：不透明度过半为实心底，否则浅色底；白色、完全透明或在深色卡片上看不清的深色按普通灰色底显示 |
| `IconButton` | `IconButton(id, icon: IslandImage, description: String, requiresUnlock = false)` | 圆形图标按钮，显示在**第一行右侧**，灰色圆形底。`description` 为读屏说明，≤ 32 字符 |
| `BarIconButton` | `BarIconButton(id, icon: IslandImage, description: String, requiresUnlock = false)` | 底部图标按钮：只有图标、无文字，在底部另起一排并列。`description` ≤ 32 字符 |
| `ToggleButton` | `ToggleButton(id, label, checked: Boolean, icon = null, requiresUnlock = false)` | 开关：底部并排一排，打开时以强调色显示。**星河岛不会自行切换**，请在 `onAction` 处理后用同编号更新 `checked` |

各类属性与构造参数同名。

### `ButtonStyle`

```kotlin
enum class ButtonStyle { NORMAL, PRIMARY, DESTRUCTIVE }
```

| 取值 | 视觉 |
| --- | --- |
| `NORMAL` | 灰色底（不透明度 15% 白色玻璃质感） |
| `PRIMARY` | 强调色实心底，用于最主要操作 |
| `DESTRUCTIVE` | 浅红底红字，用于删除、取消等 |

### 固定控件

```kotlin
enum class TimerControl(val actionId: String) { PAUSE("pause"), RESUME("resume"), STOP("stop") }
enum class MediaControl(val actionId: String) { PREVIOUS("prev"), PLAY_PAUSE("play_pause"), NEXT("next") }
```

- `TimerControl`：计时类强调卡片右侧的圆形按钮。`PAUSE` / `RESUME` 为强调色实心圆钮，`STOP` 为灰色圆钮（叉号）。
- `MediaControl`：音乐卡片播放按键，`PLAY_PAUSE` 图形随播放状态切换。
- `StatusCard.ACTION_STOP = "stop"`：状态卡片的红色停止圆钮。

### 数量上限（每张卡片，唯一权威表，06 不再重复）

| 种类 | 上限 |
| --- | --- |
| 文字按钮 `TextButton` | 4 |
| 圆形按钮（`IconButton`，以及 `TimerControl`、状态卡片停止圆钮） | 3 |
| 底部图标按钮 `BarIconButton` | 4 |
| **以上三类合计** | **4** |
| 开关按钮 `ToggleButton` | 3（另计） |

### 各卡片可用的按钮

| 卡片 | TextButton | IconButton | BarIconButton | ToggleButton | 固定按钮 |
| --- | --- | --- | --- | --- | --- |
| 通用、进度、明细 | 可用 | 可用 | 可用 | 可用 | 无 |
| 强调 | 可用 | 可用 | 可用 | 可用 | `TimerControl`（仅计时） |
| 状态 | 可用 | 可用 | 可用 | 可用 | 停止圆钮 |
| 大图、消息 | 可用 | **不可用** | 可用 | 可用 | 无 |
| 音乐 | 不可用 | 不可用 | 不可用 | 不可用 | `MediaControl` |
| 对称 | 不可用 | 不可用 | 不可用 | 不可用 | 无 |

### `ButtonLayout`（0.1.2 新增，本库唯一详写处）

```kotlin
class ButtonLayout @JvmOverloads constructor(
    val perRow: Int = 3,
    val rowFill: RowFill = RowFill.EQUAL,
    val loneFill: LoneFill = LoneFill.HALF,
    val loneAlign: LoneAlign = LoneAlign.CENTER,
)
```

| 参数 | 取值 | 说明 |
| --- | --- | --- |
| `perRow` | 1~4 | 一排最多几个文字按钮；超出范围构造时抛 `IllegalArgumentException` |
| `rowFill` | `EQUAL`（默认）、`WEIGHT`、`CONTENT` | 等分；带底色的按钮（`PRIMARY` 或设了 `fillColor`）占两份宽；按文字长短排 |
| `loneFill` | `HALF`（默认）、`FULL`、`CONTENT` | 只剩一个时占半排、整排、按文字长短 |
| `loneAlign` | `START`、`CENTER`（默认）、`END` | 只剩一个时靠左、居中、靠右；`loneFill = FULL` 时无效 |

```kotlin
GenericCard.Builder("停车计费")
    .addButton(TextButton("pay", "缴费", ButtonStyle.PRIMARY))
    .addButton(TextButton("later", "稍后"))
    .addButton(TextButton("detail", "详情"))
    .setButtonLayout(ButtonLayout(perRow = 3))
    .build()
```

- `setButtonLayout` 存在于 7 个卡片 Builder 上：`GenericCard`、`ProgressCard`、`EmphasisCard`、`DetailsCard`、`StatusCard`、`PictureCard`、`MessageCard`（`[SDK]` 字节码核对）。音乐与对称没有文字按钮，也没有该方法。
- 只影响底部排开的文字按钮；圆形按钮、底部图标按钮、开关不受影响。
- **默认每排三个**、各占三分之一；四个时平均分两排、各两个；一排只剩一个时占半排居中。不设置即按默认。
- 该设置只在星河岛 **0.1.2 及以上**生效；更早的星河岛读不到它，仍按每排两个排，不会报错。

## 4.10 线程与异常约定（`[站点]` `develop-quickstart.html`）

| 约定 | 内容 |
| --- | --- |
| 阻塞调用 | `start()`、`update()`、`end()`、`endAll()`、`listMine()` **会等待星河岛处理完毕才返回**，须在后台线程调用 |
| 回调线程 | 所有回调均在**主线程**执行 |
| 异常时机 | 文字超长、按钮超限、必填缺失等在**构造 / `setXxx` / `build()`** 立即抛出，附中文说明；不会拖到提交时 |
| 事件可靠性 | 回调「尽力送达」，不作为业务唯一依据 |
