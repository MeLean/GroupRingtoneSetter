package com.milen.grounpringtonesetter.data

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class MediaStoreAudioContractTest {

    @Test
    fun testOwnedWavCanBeInsertedQueriedReusedAndDeletedByExactUri() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.WRITE_EXTERNAL_STORAGE"
            ).close()
        }

        val resolver = context.contentResolver
        val displayName = "codex-contract-${System.nanoTime()}.wav"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, displayName.removeSuffix(".wav"))
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.IS_RINGTONE, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Ringtones/LabelRingtoneSetterTests")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val uri = requireNotNull(
            resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        )

        try {
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output).write(oneSecondSilentWav())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }

            resolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.MIME_TYPE),
                null,
                null,
                null,
            ).use { cursor ->
                requireNotNull(cursor)
                assertTrue(cursor.moveToFirst())
                assertEquals(displayName, cursor.getString(0))
                assertTrue(cursor.getString(1) in setOf("audio/wav", "audio/x-wav"))
            }
            assertTrue(requireNotNull(resolver.openInputStream(uri)).use { it.read() >= 0 })
        } finally {
            assertEquals(1, resolver.delete(uri, null, null))
        }
    }

    private fun oneSecondSilentWav(): ByteArray {
        val sampleRate = 8_000
        val audioSize = sampleRate
        return ByteBuffer.allocate(44 + audioSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII))
            .putInt(36 + audioSize)
            .put("WAVE".toByteArray(Charsets.US_ASCII))
            .put("fmt ".toByteArray(Charsets.US_ASCII))
            .putInt(16)
            .putShort(1.toShort())
            .putShort(1.toShort())
            .putInt(sampleRate)
            .putInt(sampleRate)
            .putShort(1.toShort())
            .putShort(8.toShort())
            .put("data".toByteArray(Charsets.US_ASCII))
            .putInt(audioSize)
            .put(ByteArray(audioSize))
            .array()
    }
}
