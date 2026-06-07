package com.milen.grounpringtonesetter.backup

import android.net.FakeUri
import com.milen.grounpringtonesetter.ui.defaulttones.DeviceDefaultToneType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GrsArchiveRoundTripTest {

    @Test
    fun `archive writes plain manifest and selected tone assets`() {
        val firstUri = FakeUri("content://tone/first")
        val secondUri = FakeUri("content://tone/second")
        val firstBytes = byteArrayOf(1, 2, 3)
        val secondBytes = byteArrayOf(4, 5)
        val snapshot = sampleSnapshot(
            tones = listOf(
                GrsToneSnapshot(
                    id = "first",
                    displayName = "First.mp3",
                    category = GrsToneCategory.RINGTONE,
                    archivePath = "assets/first.mp3",
                    sizeBytes = firstBytes.size.toLong()
                ),
                GrsToneSnapshot(
                    id = "second",
                    displayName = "Second.mp3",
                    category = GrsToneCategory.NOTIFICATION,
                    archivePath = "assets/second.mp3",
                    sizeBytes = secondBytes.size.toLong()
                )
            )
        )
        val archive = ByteArrayOutputStream()

        val writtenAssets = GrsArchiveWriter { uri ->
            when (uri.toString()) {
                firstUri.toString() -> ByteArrayInputStream(firstBytes)
                secondUri.toString() -> ByteArrayInputStream(secondBytes)
                else -> null
            }
        }.write(
            output = archive,
            request = GrsArchiveWriteRequest(
                snapshot = snapshot,
                assets = listOf(
                    GrsToneAsset(
                        toneId = "first",
                        archivePath = "assets/first.mp3",
                        sourceUri = firstUri,
                        sizeBytes = firstBytes.size.toLong()
                    ),
                    GrsToneAsset(
                        toneId = "second",
                        archivePath = "assets/second.mp3",
                        sourceUri = secondUri,
                        sizeBytes = secondBytes.size.toLong()
                    )
                )
            )
        )

        val content = GrsArchiveReader().read(
            input = ByteArrayInputStream(archive.toByteArray()),
            includedAssetPaths = setOf("assets/second.mp3")
        )

        assertEquals(2, writtenAssets)
        assertEquals(snapshot, content.snapshot)
        assertEquals(setOf("assets/second.mp3"), content.assetBytesByPath.keys)
        assertArrayEquals(secondBytes, content.assetBytesByPath.getValue("assets/second.mp3"))
    }

    @Test
    fun `readSnapshot reads manifest without extracting assets`() {
        val assetPath = "assets/large.mp3"
        val snapshot = sampleSnapshot(
            tones = listOf(
                GrsToneSnapshot(
                    id = "large",
                    displayName = "Large.mp3",
                    category = GrsToneCategory.RINGTONE,
                    archivePath = assetPath,
                    sizeBytes = 4L
                )
            )
        )
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.xml"))
            zip.write(GrsManifestCodec.encode(snapshot).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(assetPath))
            zip.write(byteArrayOf(9, 8, 7, 6))
            zip.closeEntry()
        }

        val readSnapshot = GrsArchiveReader().readSnapshot(
            input = ByteArrayInputStream(archive.toByteArray())
        )

        assertEquals(snapshot, readSnapshot)
    }

    @Test
    fun `manifest codec preserves group names and tone metadata`() {
        val snapshot = sampleSnapshot(
            groupName = "Family & Friends",
            toneDisplayName = "Bell <soft>.mp3"
        )

        val decoded = GrsManifestCodec.decode(GrsManifestCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `manifest codec removes invalid xml control characters`() {
        val snapshot = sampleSnapshot(
            groupName = "Family\u0000Friends",
            toneDisplayName = "Bell\u0001soft.mp3"
        )

        val encoded = GrsManifestCodec.encode(snapshot)
        val decoded = GrsManifestCodec.decode(encoded)

        assertFalse(encoded.contains('\u0000'))
        assertFalse(encoded.contains('\u0001'))
        assertEquals("FamilyFriends", decoded.groupTones.single().groupName)
        assertEquals("Bellsoft.mp3", decoded.tones.single().displayName)
    }

    @Test(expected = GrsArchiveException::class)
    fun `reader rejects archives without grs manifest`() {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("not-manifest.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }

        GrsArchiveReader().readSnapshot(ByteArrayInputStream(archive.toByteArray()))
    }

    @Test
    fun `writer progress reaches one hundred`() {
        val progressValues = mutableListOf<Int>()

        GrsArchiveWriter { null }.write(
            output = ByteArrayOutputStream(),
            request = GrsArchiveWriteRequest(
                snapshot = sampleSnapshot(tones = emptyList()),
                assets = emptyList()
            ),
            onProgress = progressValues::add
        )

        assertFalse(progressValues.isEmpty())
        assertTrue(progressValues.zipWithNext().all { (previous, next) -> next >= previous })
        assertEquals(100, progressValues.last())
    }

    private fun sampleSnapshot(
        groupName: String = "Family",
        toneDisplayName: String = "Bell.mp3",
        tones: List<GrsToneSnapshot> = listOf(
            GrsToneSnapshot(
                id = "tone",
                displayName = toneDisplayName,
                category = GrsToneCategory.RINGTONE,
                archivePath = "assets/tone.mp3",
                sizeBytes = 3L
            )
        ),
    ): GrsBackupSnapshot =
        GrsBackupSnapshot(
            createdAtEpochMillis = 1L,
            appVersionName = "1.0",
            sourceLabel = "On device",
            groupTones = listOf(
                GrsGroupToneSnapshot(
                    groupName = groupName,
                    toneId = tones.firstOrNull()?.id.orEmpty()
                )
            ).filter { it.toneId.isNotBlank() },
            defaultTones = listOf(
                GrsDefaultToneSnapshot(
                    type = DeviceDefaultToneType.RINGTONE,
                    toneId = tones.firstOrNull()?.id.orEmpty(),
                    displayName = toneDisplayName
                )
            ).filter { it.toneId.isNotBlank() },
            tones = tones
        )
}
