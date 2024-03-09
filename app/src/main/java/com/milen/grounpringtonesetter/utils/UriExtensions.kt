package com.milen.grounpringtonesetter.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.milen.grounpringtonesetter.R

fun Context.areAllPermissionsGranted(permissions: List<String>): Boolean =
    permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

fun Uri.getFileNameOrEmpty(context: Context): String {
    val errorMsg = context.getString(R.string.file_name_not_accessible)

    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)

    try {
        context.contentResolver.query(this, projection, null, null, null)
            ?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return it.getString(nameIndex)
                    }
                }
            }
    } catch (e: SecurityException) {
        return errorMsg
    }

    return errorMsg
}

fun audioPermissionSdkBased() =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_AUDIO
        else -> Manifest.permission.READ_EXTERNAL_STORAGE
    }