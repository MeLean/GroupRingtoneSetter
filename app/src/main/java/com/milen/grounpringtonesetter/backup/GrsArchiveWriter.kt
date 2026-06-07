package com.milen.grounpringtonesetter.backup

import android.content.Context
import android.net.Uri
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

internal data class GrsArchiveWriteRequest(
    val snapshot: GrsBackupSnapshot,
    val assets: List<GrsToneAsset>,
)

internal class GrsArchiveWriter(
    private val openAssetInputStream: (Uri) -> InputStream?,
) {
    constructor(context: Context) : this(
        openAssetInputStream = { uri -> context.contentResolver.openInputStream(uri) }
    )

    fun write(
        output: OutputStream,
        request: GrsArchiveWriteRequest,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int {
        val progress = GrsWriteProgress(
            assets = request.assets,
            onProgress = onProgress,
            isCancelled = isCancelled
        )
        return runCatching {
            progress.report(0)
            ZipOutputStream(CancellableOutputStream(output, progress)).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
                zip.write(GrsManifestCodec.encode(request.snapshot).toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                progress.report(10)

                request.assets.forEach { asset ->
                    progress.ensureActive()
                    zip.putNextEntry(ZipEntry(asset.archivePath))
                    openAssetInputStream(asset.sourceUri)?.use { input ->
                        copyAsset(
                            input = input,
                            output = zip,
                            asset = asset,
                            progress = progress
                        )
                    } ?: throw GrsArchiveException("Unable to read tone asset")
                    zip.closeEntry()
                    progress.finishAsset(asset)
                }
            }
            progress.report(100)
            request.assets.size
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            if (error is GrsArchiveException) throw error
            throw GrsArchiveException("Unable to write backup archive", error)
        }
    }

    private fun copyAsset(
        input: InputStream,
        output: OutputStream,
        asset: GrsToneAsset,
        progress: GrsWriteProgress,
    ) {
        val buffer = ByteArray(COPY_BUFFER_SIZE_BYTES)
        var copiedBytes = 0L
        while (true) {
            progress.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copiedBytes += read.toLong()
            progress.reportAssetBytesCopied(asset, copiedBytes)
        }
    }

    private class CancellableOutputStream(
        output: OutputStream,
        private val progress: GrsWriteProgress,
    ) : FilterOutputStream(output) {
        override fun write(value: Int) {
            progress.ensureActive()
            out.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            progress.ensureActive()
            out.write(buffer, offset, length)
        }

        override fun flush() {
            progress.ensureActive()
            out.flush()
        }

        override fun close() {
            progress.ensureActive()
            out.close()
        }
    }

    private class GrsWriteProgress(
        assets: List<GrsToneAsset>,
        private val onProgress: (Int) -> Unit,
        private val isCancelled: () -> Boolean,
    ) {
        private val assetWeights = assets.associate { asset -> asset.archivePath to asset.progressWeight() }
        private val totalAssetWeight = assetWeights.values.sum().takeIf { it > 0L } ?: 1L
        private var completedAssetWeight = 0L
        private var lastPercent = -1

        fun ensureActive() {
            if (isCancelled()) {
                throw CancellationException("Backup export cancelled")
            }
        }

        fun report(percent: Int) {
            ensureActive()
            val normalized = percent.coerceIn(0, 100)
            if (normalized <= lastPercent) return
            lastPercent = normalized
            onProgress(normalized)
        }

        fun reportAssetBytesCopied(asset: GrsToneAsset, copiedBytes: Long) {
            val assetWeight = assetWeights[asset.archivePath] ?: return
            val currentAssetWeight = copiedBytes.coerceAtMost(assetWeight)
            reportAssetProgress(completedAssetWeight + currentAssetWeight)
        }

        fun finishAsset(asset: GrsToneAsset) {
            completedAssetWeight += assetWeights[asset.archivePath] ?: 0L
            reportAssetProgress(completedAssetWeight)
        }

        private fun reportAssetProgress(currentAssetWeight: Long) {
            val percent = ASSET_START_PROGRESS +
                    ((currentAssetWeight * ASSET_PROGRESS_RANGE) / totalAssetWeight).toInt()
            report(percent)
        }

        private fun GrsToneAsset.progressWeight(): Long =
            sizeBytes?.takeIf { it > 0L } ?: UNKNOWN_ASSET_WEIGHT_BYTES
    }

    private companion object {
        const val ENTRY_MANIFEST = "manifest.xml"
        const val ASSET_START_PROGRESS = 10
        const val ASSET_PROGRESS_RANGE = 85
        const val COPY_BUFFER_SIZE_BYTES = 64 * 1024
        const val UNKNOWN_ASSET_WEIGHT_BYTES = 1024L * 1024L
    }
}
