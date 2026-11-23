package com.milen.grounpringtonesetter.testing

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun oneSecondSilentWav(): ByteArray {
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

/** 2.5 seconds of mono, 16-bit little-endian PCM; independent of emulator media/network. */
internal fun pcmTestWav(frequencyHz: Double = 440.0): ByteArray {
    val sampleRate = 16_000
    val samples = sampleRate * 5 / 2
    val dataSize = samples * 2
    val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize)
        .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        .putShort(1.toShort()).putShort(1.toShort()).putInt(sampleRate)
        .putInt(sampleRate * 2).putShort(2.toShort()).putShort(16.toShort())
        .put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
    repeat(samples) { index ->
        buffer.putShort((kotlin.math.sin(2 * Math.PI * frequencyHz * index / sampleRate) * 4000).toInt().toShort())
    }
    return buffer.array()
}
