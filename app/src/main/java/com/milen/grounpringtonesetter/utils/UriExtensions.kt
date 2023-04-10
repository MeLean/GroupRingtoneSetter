package com.milen.grounpringtonesetter.utils

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.milen.grounpringtonesetter.R


fun Uri?.getFileNameOrEmpty(context: Context): String =
    this?.let {
        try {
            val cursor = context.contentResolver.query(
                this,
                arrayOf(MediaStore.Audio.Media.DATA),
                null,
                null,
                null
            )
            cursor?.use {
                val columnIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                it.moveToFirst()
                it.getString(columnIndex).substringAfterLast("/")
            }
        } catch (e: SecurityException) {
            context.getString(R.string.file_not_accessible)
        }
    } ?: ""