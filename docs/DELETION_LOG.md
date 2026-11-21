# DELETION_LOG（死代码删除台账——规范要求：先证明再删除）

| 状态 | 文件 | 函数 | 判定依据 | 回滚方式 |
|---|---|---|---|---|
| 已删 | capability/link/LinkPatterns.kt | hasLink | CAREFUL：全项目零调用+零字符串引用+零反射+零 manifest 引用（grep 三验通过） | git 历史或版本备份恢复 |
| FAIL | capability/link/LinkPatterns.kt | hasEmail | CAREFUL：全项目零调用+零字符串引用+零反射+零 manifest 引用（grep 三验通过） | git 历史或版本备份恢复 |
| 已删 | lyric/KuwoLyric.kt | isZlib | CAREFUL：全项目零调用+零字符串引用+零反射+零 manifest 引用（grep 三验通过） | git 历史或版本备份恢复 |
| FAIL | settings/ModulePrefs.kt | isHintOnly | CAREFUL：全项目零调用+零字符串引用+零反射+零 manifest 引用（grep 三验通过） | git 历史或版本备份恢复 |
| FAIL | ui/page/PickerSheet.kt | showForBrowser | CAREFUL：全项目零调用+零字符串引用+零反射+零 manifest 引用（grep 三验通过） | git 历史或版本备份恢复 |
| 已删 | ui/widget/Glass.kt | applyBlur | CAREFUL：全项目零调用+零字符串引用+零反射+零 manifest 引用（grep 三验通过） | git 历史或版本备份恢复 |
| 已删 | ui/widget/Glass.kt | clipRounded | CAREFUL：全项目零调用+零字符串引用+零反射+零 manifest 引用（grep 三验通过） | git 历史或版本备份恢复 |
| 已删 | ui/widget/Motion.kt | animateTrack | CAREFUL：全项目零调用+零字符串引用+零反射+零 manifest 引用（grep 三验通过） | git 历史或版本备份恢复 |

| 已删 | settings/ModulePrefs.kt | KEY_LINK_HINT_ONLY | CAREFUL：isHintOnly 删除后孤立（全项目零引用）；常量属 RISKY 边界（RemotePreferences 键）但 grep 三验+strings.xml 均无引用 | git 历史恢复 |

## 事故记录（2026-10-28）

### Motion.kt 恢复事故
- 现象：删除 animateTrack 的正则把 Motion.kt 全部函数吞掉（pressFeedback/breathe/fadeInUp 三函数被误删），UI 全站在调 → 编译必挂。
- 发现方式：删除后四件套的"未用 import"异常暴露（View/ValueAnimator 等 import 无主）→ 壳文件检查（行数 14）→ 引用扫描发现全站调用。
- 恢复：从 5.2 备份整体复制 Motion.kt，再按括号深度精确剔除 animateTrack 单函数。
- 教训：**删除脚本必须输出删除前后行数对比**，行数骤降=吞了邻居；四件套必须加"壳文件检查"（object/class 空体+存在跨文件调用=事故）。

### Glass.kt 空壳
- applyBlur/clipRounded 删除正确（5.2 起就被 UIKit 的 drawable 方案替代，零消费者）。
- 剩空 object 壳：RISKY 级只报告——无消费者但保留待人工确认后整体删除文件。

## 7.11（74）删除台账

| 状态 | 文件 | 函数 | 判定依据 | 回滚方式 |
|---|---|---|---|---|
| 已删 | app/ManualApkScan.kt | 整文件 | 测试页精简后全项目零调用、零字符串引用、零 manifest/反射引用（`grep -rn "ManualApkScan" app/src` 空）；其唯一职责「扫最近 7 天安装包演示出卡」属于测试功能，审核要求删除 | git 历史或版本备份恢复 |
| 已删 | app/SmartApkIntake.kt | runManual / tooOld(forceFresh) / execute(forceFresh) / intake(allowed, forceFresh) | 唯一调用方是 ManualApkScan；删除后 `runManual`、`forceFresh` 全仓 0 引用，`runFromThirdParty` 收为 2 参 | git 历史恢复 |
| 已删 | ui/shell/TestPage.kt | sendLaunch / TEST_BT / launchSection 各按钮 | 审核要求：测试页只保留「链接卡 / 手机号卡 / 刷新连接状态」，删除 WiFi 设置页、蓝牙断开、关闭蓝牙、蓝牙设置页、用浏览器打开链接等会真实动作的测试 | git 历史恢复 |
| 已删 | ui/shell/TestPage.kt | lyricSection / lingerSection / fileSection 测试入口 | 歌词与卡片时长自检不产生卡片、扫描测试已随 ManualApkScan 删除；保留 3 项主功能测试即可 | git 历史恢复 |
