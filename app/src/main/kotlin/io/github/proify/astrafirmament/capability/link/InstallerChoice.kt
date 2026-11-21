package io.github.proify.astrafirmament.capability.link

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri

/**
 * 设置页用：列出手机上真正的 APK 安装器。
 *
 * 通用判定（无任何硬编码包名，真机数据驱动）：
 *  A. granted INSTALL_PACKAGES —— platform 签名的系统/厂商安装器；
 *  B. REQUEST_INSTALL_PACKAGES + activity 类名含 install 语义 + 包不具备
 *     浏览器能力（VIEW+https+BROWSABLE 三要素探测）——第三方专职安装器。
 * 排除：浏览器（夸克）、内容平台（芥末社区）、终端（Termux）、文件查看器。
 * queries 需声明 VIEW+archive（见 AndroidManifest）。
 */
object InstallerChoice {

    data class InstallerApp(val label: String, val packageName: String)

    fun installed(context: Context): List<InstallerApp> {
        val pm = context.packageManager
        // 双探测合并（真机实锤：正经安装器只声明 scheme=content 的 archive
        // filter——裸 type 探测漏掉它们，而裸 type 列表里反而是芥末/夸克/
        // 终端这类杂项。两个探测结果并集去重才是全量。）
        val typeProbe = Intent(Intent.ACTION_VIEW).apply {
            type = "application/vnd.android.package-archive"
        }
        val contentProbe = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://xinghe/probe.apk"),
                "application/vnd.android.package-archive")
        }
        val resolved = runCatching {
            (pm.queryIntentActivities(typeProbe, PackageManager.MATCH_ALL) +
                pm.queryIntentActivities(contentProbe, PackageManager.MATCH_ALL))
                .distinctBy { it.activityInfo?.name }
        }.getOrDefault(emptyList())
        // 正经安装器过滤（真机数据驱动的通用判定，双通道任一命中）：
        //  A. granted INSTALL_PACKAGES —— platform 签名的系统/厂商安装器
        //     （com.android.packageinstaller、OppoPackageInstaller…）。
        //  B. REQUEST_INSTALL_PACKAGES 且【本 activity 是专职安装入口】：
        //     类名含 install 语义 且【不带 BROWSABLE category】——
        //     BROWSABLE 是浏览器/内容平台专用的（实测夸克的 apk_installer
        //     带 BROWSABLE 被此条滤掉；芥末社区的 .index 无 install 语义被滤掉；
        //     InstallX 的 InstallerActivity 双条件命中保留）。
        fun isRealInstaller(pkg: String, actName: String): Boolean = runCatching {
            val pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            val req = pi.requestedPermissions ?: return@runCatching false
            val flags = pi.requestedPermissionsFlags
            // A. 系统签名安装器（com.android.packageinstaller / 厂商安装器）
            val i1 = req.indexOf(android.Manifest.permission.INSTALL_PACKAGES)
            val grantedSystem = i1 >= 0 && (flags?.getOrNull(i1)?.and(
                PackageInfo.REQUESTED_PERMISSION_GRANTED) ?: 0) != 0
            if (grantedSystem) return@runCatching true
            // B. 第三方专职安装器（InstallX 类）：请求安装权限 + 专职入口语义
            //    + 【包本身不处理 http/https】（处理网页的一律是浏览器/内容平台——
            //      实测夸克带 BROWSABLE+VIEW http 的包被此条排除；InstallX 无浏览能力保留）
            val requestInstaller = android.Manifest.permission.REQUEST_INSTALL_PACKAGES in req
            if (!requestInstaller) return@runCatching false
            val nameSaysInstall = actName.contains("install", ignoreCase = true)
            if (!nameSaysInstall) return@runCatching false
            // 浏览器能力探测：VIEW+https 且【带 BROWSABLE category】——正经浏览器
            // 都带（夸克实测带）；InstallX 的 MIME 通配 filter 不带 BROWSABLE，
            // 不会被此探测误伤（pm query 通配蹭进结果的问题靠 category 区分）。
            val browseProbe = Intent(Intent.ACTION_VIEW, Uri.parse("https://probe.example.com")).apply {
                addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            }
            val canBrowse = pm.queryIntentActivities(browseProbe, 0).any {
                it.activityInfo?.packageName == pkg
            }
            !canBrowse
        }.getOrDefault(false)
        return resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val actName = info.activityInfo?.name.orEmpty()
                if (!isRealInstaller(pkg, actName)) return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                InstallerApp(label, pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    }
}
