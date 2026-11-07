package com.rahcare.termuxbrowser

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

// অ্যাপের নিজের APK-টা "Daily Browser.apk" নামে শেয়ার করতে দেয়: শেয়ার-শিটে ও যাকে পাঠানো হয় তার ফোনে এই নামই দেখায়
class ApkShareProvider : ContentProvider() {
    companion object {
        const val NAME = "Daily Browser.apk"
        fun uri(c: Context): Uri = Uri.Builder().scheme("content").authority(c.packageName + ".apkshare").appendPath(NAME).build()
    }

    private fun apk(): File = File(context!!.applicationInfo.sourceDir)

    override fun onCreate() = true

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cols: Array<String> = projection?.map { it }?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cur = MatrixCursor(cols)
        val row = arrayOfNulls<Any>(cols.size)
        for (i in cols.indices) {
            row[i] = when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> NAME
                OpenableColumns.SIZE -> apk().length()
                else -> null
            }
        }
        cur.addRow(row)
        return cur
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(apk(), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
