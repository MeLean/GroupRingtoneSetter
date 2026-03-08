package com.milen.grounpringtonesetter.utils

internal fun <T> chunkBySize(
    items: List<T>,
    maxChunkSize: Int,
): List<List<T>> {
    require(maxChunkSize > 0) { "maxChunkSize must be greater than 0" }
    if (items.isEmpty()) return emptyList()
    return items.chunked(maxChunkSize)
}
