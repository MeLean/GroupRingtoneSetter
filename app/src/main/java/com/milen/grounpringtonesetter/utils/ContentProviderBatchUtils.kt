package com.milen.grounpringtonesetter.utils

internal fun <T> chunkBySize(
    items: List<T>,
    maxChunkSize: Int,
): List<List<T>> {
    require(maxChunkSize > 0) { "maxChunkSize must be greater than 0" }
    if (items.isEmpty()) return emptyList()
    return items.chunked(maxChunkSize)
}

internal fun <T> chunkBySizeAndBytes(
    items: List<T>,
    maxChunkSize: Int,
    maxChunkBytes: Int,
    itemBytes: (T) -> Int,
): List<List<T>> {
    require(maxChunkSize > 0) { "maxChunkSize must be greater than 0" }
    require(maxChunkBytes > 0) { "maxChunkBytes must be greater than 0" }
    val chunks = mutableListOf<List<T>>()
    val current = mutableListOf<T>()
    var currentBytes = 0
    items.forEach { item ->
        val bytes = itemBytes(item)
        require(bytes in 0..maxChunkBytes) { "One operation exceeds the batch byte limit" }
        if (current.size == maxChunkSize || currentBytes > maxChunkBytes - bytes) {
            chunks += current.toList()
            current.clear()
            currentBytes = 0
        }
        current += item
        currentBytes += bytes
    }
    if (current.isNotEmpty()) chunks += current.toList()
    return chunks
}
