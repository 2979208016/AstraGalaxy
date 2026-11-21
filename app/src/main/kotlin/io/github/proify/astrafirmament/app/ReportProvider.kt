package io.github.proify.astrafirmament.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * 报告分享 Provider（原生 ContentProvider，无 androidx 依赖）。
 *
 * 只读暴露数据目录里的 xinghe_report.zip——系统分享面板（ACTION_SEND +
 * EXTRA_STREAM）需要 content:// URI 才能把文件交给微信/QQ/网盘。
 * 仅此一个文件，其余一概拒绝（openFile 白名单校验文件名）。
 */
class ReportProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode.contains("w")) throw SecurityException("read only")
        val name = uri.lastPathSegment ?: throw SecurityException("no name")
        if (name != "xinghe_report.zip") throw SecurityException("not report")
        val f = File(context?.filesDir, name)
        if (!f.isFile) throw SecurityException("no report yet")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/zip"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
