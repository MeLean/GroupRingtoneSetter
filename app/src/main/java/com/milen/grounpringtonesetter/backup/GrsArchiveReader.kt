package com.milen.grounpringtonesetter.backup

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

internal class GrsArchiveReader {

    fun read(
        input: InputStream,
        includedAssetPaths: Set<String>? = null,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): BackupArchiveContent {
        var snapshot: GrsBackupSnapshot? = null
        val assetBytesByPath = linkedMapOf<String, ByteArray>()
        var assetSizeByPath = emptyMap<String, Long>()
        var totalAssetBytes = 0L
        var extractedAssetBytes = 0L
        var extractedAssetCount = 0
        onProgress(0)

        try {
            ZipInputStream(NonClosingInputStream(input)).use { zip ->
                while (true) {
                    ensureReadActive(isCancelled)
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == ENTRY_MANIFEST -> {
                            val bytes = zip.readBytesFromCurrentEntry(isCancelled)
                            snapshot = GrsManifestCodec.decode(bytes.toString(StandardCharsets.UTF_8))
                            assetSizeByPath = snapshot.orEmptyAssetSizeByPath(includedAssetPaths)
                            totalAssetBytes = assetSizeByPath.values.sum()
                            onProgress(20)
                        }

                        entry.name.startsWith(ASSET_PREFIX) -> {
                            if (includedAssetPaths != null && entry.name !in includedAssetPaths) {
                                zip.drainCurrentEntry(isCancelled)
                            } else {
                                val assetSize = assetSizeByPath[entry.name]
                                val bytes = zip.readBytesFromCurrentEntry(
                                    isCancelled = isCancelled,
                                    onBytesRead = { copiedBytes ->
                                        if (totalAssetBytes > 0L && assetSize != null) {
                                            val boundedCopiedBytes = copiedBytes.coerceAtMost(assetSize)
                                            reportAssetProgress(
                                                extractedAssetBytes + boundedCopiedBytes,
                                                totalAssetBytes,
                                                onProgress
                                            )
                                        }
                                    }
                                )
                                assetBytesByPath[entry.name] = bytes
                                extractedAssetBytes += assetSize ?: 0L
                                extractedAssetCount += 1
                                if (totalAssetBytes <= 0L) {
                                    reportUnknownSizeAssetProgress(
                                        extractedAssetCount = extractedAssetCount,
                                        snapshot = snapshot,
                                        onProgress = onProgress
                                    )
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            onProgress(95)
            return BackupArchiveContent(
                snapshot = snapshot ?: throw GrsArchiveException("Manifest is missing"),
                assetBytesByPath = assetBytesByPath
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error is GrsArchiveException) throw error
            throw GrsArchiveException(error.asArchiveReadErrorMessage(), error)
        }
    }

    fun readSnapshot(
        input: InputStream,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): GrsBackupSnapshot {
        onProgress(0)
        try {
            ZipInputStream(NonClosingInputStream(input)).use { zip ->
                while (true) {
                    ensureReadActive(isCancelled)
                    val entry = zip.nextEntry ?: break
                    if (entry.name == ENTRY_MANIFEST) {
                        val bytes = zip.readBytesFromCurrentEntry(isCancelled)
                        onProgress(95)
                        return GrsManifestCodec.decode(bytes.toString(StandardCharsets.UTF_8))
                    }
                    zip.closeEntry()
                }
            }
            throw GrsArchiveException("Manifest is missing")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error is GrsArchiveException) throw error
            throw GrsArchiveException(error.asArchiveReadErrorMessage(), error)
        }
    }

    private fun Throwable.asArchiveReadErrorMessage(): String {
        val detail = message?.takeIf { it.isNotBlank() } ?: javaClass.name
        return "Unable to read backup archive: ${javaClass.simpleName}: $detail"
    }

    private fun GrsBackupSnapshot?.orEmptyAssetSizeByPath(
        includedAssetPaths: Set<String>?,
    ): Map<String, Long> =
        this?.tones
            .orEmpty()
            .mapNotNull { tone ->
                if (includedAssetPaths != null && tone.archivePath !in includedAssetPaths) {
                    return@mapNotNull null
                }
                val sizeBytes = tone.sizeBytes?.takeIf { it > 0L } ?: return@mapNotNull null
                tone.archivePath to sizeBytes
            }
            .toMap()

    private fun reportAssetProgress(
        extractedAssetBytes: Long,
        totalAssetBytes: Long,
        onProgress: (Int) -> Unit,
    ) {
        if (totalAssetBytes <= 0L) return
        onProgress(ASSET_START_PROGRESS + ((extractedAssetBytes.coerceAtMost(totalAssetBytes) *
                ASSET_PROGRESS_RANGE) / totalAssetBytes).toInt())
    }

    private fun reportUnknownSizeAssetProgress(
        extractedAssetCount: Int,
        snapshot: GrsBackupSnapshot?,
        onProgress: (Int) -> Unit,
    ) {
        val totalAssetCount = snapshot?.tones
            .orEmpty()
            .count()
            .coerceAtLeast(1)
        onProgress(ASSET_START_PROGRESS +
                ((extractedAssetCount.coerceAtMost(totalAssetCount) *
                        ASSET_PROGRESS_RANGE) / totalAssetCount))
    }

    private fun InputStream.readBytesFromCurrentEntry(
        isCancelled: () -> Boolean,
        onBytesRead: (Long) -> Unit = {},
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_SIZE_BYTES)
        var copiedBytes = 0L
        while (true) {
            ensureReadActive(isCancelled)
            val count = read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            copiedBytes += count.toLong()
            onBytesRead(copiedBytes)
        }
        return output.toByteArray()
    }

    private fun InputStream.drainCurrentEntry(isCancelled: () -> Boolean) {
        val buffer = ByteArray(COPY_BUFFER_SIZE_BYTES)
        while (true) {
            ensureReadActive(isCancelled)
            if (read(buffer) < 0) break
        }
    }

    private fun ensureReadActive(isCancelled: () -> Boolean) {
        if (isCancelled()) {
            throw CancellationException("Backup restore cancelled")
        }
    }

    private class NonClosingInputStream(
        input: InputStream,
    ) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private companion object {
        const val ENTRY_MANIFEST = "manifest.xml"
        const val ASSET_PREFIX = "assets/"
        const val ASSET_START_PROGRESS = 20
        const val ASSET_PROGRESS_RANGE = 70
        const val COPY_BUFFER_SIZE_BYTES = 64 * 1024
    }
}
