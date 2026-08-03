package com.milen.grounpringtonesetter.backup

import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrsManifestCodecTest {

    @Test(expected = GrsArchiveException::class)
    fun `decode rejects a non grs root element`() {
        GrsManifestCodec.decode("<backup />")
    }

    @Test(expected = GrsArchiveException::class)
    fun `decode rejects wrong format identifier`() {
        GrsManifestCodec.decode(
            """<grsBackup formatId="other" schemaVersion="$GRS_SCHEMA_VERSION" />"""
        )
    }

    @Test(expected = GrsArchiveException::class)
    fun `decode rejects absent or unsupported schema version`() {
        GrsManifestCodec.decode(
            """<grsBackup formatId="$GRS_FORMAT_ID" schemaVersion="999" />"""
        )
    }

    @Test
    fun `decode applies safe defaults to optional scalar and list fields`() {
        val decoded = GrsManifestCodec.decode(
            """<grsBackup formatId="$GRS_FORMAT_ID" schemaVersion="$GRS_SCHEMA_VERSION">
                <createdAtEpochMillis>not-a-number</createdAtEpochMillis>
                <appVersionName></appVersionName>
            </grsBackup>""".trimIndent()
        )

        assertEquals(0L, decoded.createdAtEpochMillis)
        assertEquals("", decoded.appVersionName)
        assertEquals("", decoded.sourceLabel)
        assertTrue(decoded.groupTones.isEmpty())
        assertTrue(decoded.defaultTones.isEmpty())
        assertTrue(decoded.tones.isEmpty())
    }

    @Test
    fun `tone without size round trips as unknown size`() {
        val snapshot = snapshot(
            tone = GrsToneSnapshot(
                id = "tone",
                displayName = "Tone.mp3",
                category = GrsToneCategory.NOTIFICATION,
                archivePath = "assets/tone.mp3",
                sizeBytes = null,
            )
        )

        val encoded = GrsManifestCodec.encode(snapshot)
        val decoded = GrsManifestCodec.decode(encoded)

        assertFalse(encoded.contains("<sizeBytes>"))
        assertNull(decoded.tones.single().sizeBytes)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `xml metacharacters and valid unicode ranges round trip safely`() {
        val text = "tab\tline\nreturn\r & < > \" ' \uE000\uFFFD\uD83D\uDE00"
        val snapshot = snapshot(
            tone = GrsToneSnapshot(
                id = "tone",
                displayName = text,
                category = GrsToneCategory.ALARM,
                archivePath = "assets/tone.mp3",
                sizeBytes = 3,
            )
        )

        val encoded = GrsManifestCodec.encode(snapshot)
        val decoded = GrsManifestCodec.decode(encoded)

        assertTrue(encoded.contains("&amp;"))
        assertTrue(encoded.contains("&lt;"))
        assertTrue(encoded.contains("&gt;"))
        assertTrue(encoded.contains("&quot;"))
        assertTrue(encoded.contains("&apos;"))
        assertEquals(text.replace('\r', '\n'), decoded.tones.single().displayName)
    }

    @Test
    fun `invalid xml code points are removed while valid boundary characters remain`() {
        val invalidLowControl = '\u0001'
        val text = "A${invalidLowControl}B\u0020C\uD7FFD\uE000E\uFFFD"
        val snapshot = snapshot(
            tone = GrsToneSnapshot(
                id = "tone",
                displayName = text,
                category = GrsToneCategory.RINGTONE,
                archivePath = "assets/tone.mp3",
                sizeBytes = 1,
            )
        )

        val decoded = GrsManifestCodec.decode(GrsManifestCodec.encode(snapshot))

        assertEquals(text.replace(invalidLowControl.toString(), ""), decoded.tones.single().displayName)
    }

    private fun snapshot(tone: GrsToneSnapshot): GrsBackupSnapshot = GrsBackupSnapshot(
        createdAtEpochMillis = 1L,
        appVersionName = "8.5.0",
        sourceLabel = "On device",
        groupTones = listOf(GrsGroupToneSnapshot("Friends", tone.id)),
        defaultTones = listOf(
            GrsDefaultToneSnapshot(DeviceDefaultToneType.RINGTONE, tone.id, tone.displayName)
        ),
        tones = listOf(tone),
    )
}
