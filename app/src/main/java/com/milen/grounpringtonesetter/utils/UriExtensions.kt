package com.milen.grounpringtonesetter.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.milen.grounpringtonesetter.R

fun Activity.areAllPermissionsGranted(permissions: List<String>): Boolean =
    permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

fun Uri?.getFileNameOrEmpty(context: Context): String =
    this?.let {
        try {
            if (ContextCompat.checkSelfPermission(
                    context,
                    audioPermissionSdkBased()
                )
                == PackageManager.PERMISSION_GRANTED
            ) {
                val documentFile = DocumentFile.fromSingleUri(context, this)
                documentFile?.name
            } else {
                null
            }
        } catch (e: SecurityException) {
            context.getString(R.string.file_not_accessible)
        }
    } ?: ""

fun audioPermissionSdkBased() =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_AUDIO
        else -> Manifest.permission.READ_EXTERNAL_STORAGE
    }