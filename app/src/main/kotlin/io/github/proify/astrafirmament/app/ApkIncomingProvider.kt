package io.github.proify.astrafirmament.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 只暴露 apk-incoming 里的副本：卡片出岛后，安装器 / 分享面板 / 文件管理器拿到的
 * 就是这个 content://。
 *
 * 必须导出：卡片按钮由 system 身份代发（BAL 豁免），system 不是 uri owner，且 AOSP
 * 明确拒绝 system 创建 URI 授权，替不了我们向安装器/分享目标授权。因此 URI 自带
 * 访问口令（/apk-incoming/<token>/<副本名>，token 由来见 [ApkBridge.accessToken]）：
 * 知道副本名、能枚举目录都没用，拿不到 token 就构造不出可读 URI。叠加原有的
 * 「只读 + 只认 apk-incoming 里的 .apk + canonical path 校验」，`../` 越界读也不成立。
 */
class ApkIncomingProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode.contains("w")) throw SecurityException("apk-incoming is read-only")
        val file = allowedFile(uri) ?: throw FileNotFoundException("denied: " + uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = ApkBridge.MIME_APK

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = allowedFile(uri) ?: return null
        val columns = projection?.map { it }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = arrayOfNulls<Any?>(columns.size)
        columns.forEachIndexed { index, name ->
            row[index] = when (name) {
                OpenableColumns.DISPLAY_NAME -> displayName(file)
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0

    private fun allowedFile(uri: Uri): File? {
        val app = context ?: return null
        if (!ApkBridge.isOurUri(app, uri)) return null
        // 形状必须是 /apk-incoming/<token>/<副本名>，token 不对一律拒
        val segments = uri.pathSegments
        if (segments.size != 3) return null
        val name = segments[2]
        if (!isSafeName(name)) return null
        if (!ApkBridge.tokenMatches(app, name, segments[1])) return null
        val dir = ApkIncomingStore.dir(app)
        val file = File(dir, name)
        val prefix = dir.canonicalPath + File.separator
        if (!file.canonicalPath.startsWith(prefix)) return null
        return file.takeIf { it.isFile }
    }

    private fun isSafeName(name: String): Boolean =
        name == File(name).name &&
            name.endsWith(ApkBridge.APK_SUFFIX, ignoreCase = true) &&
            !name.contains("..")

    private fun displayName(file: File): String {
        val app = context ?: return file.name
        return ApkIncomingStore.displayName(app, file.name)
    }
}
