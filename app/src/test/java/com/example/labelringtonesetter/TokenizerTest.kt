package com.example.labelringtonesetter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenizerTest {

    @Test
    fun testEnglishText() = runTest {
        val text = "Hello, world! This is a sample input to count tokens."
        assertEquals(13, countTokens(text))
    }

    @Test
    fun testNonLatinText() = runTest {
        val text = "Привет, мир! 这是一个样本。"
        assertEquals(11, countTokens(text))
    }

    @Test
    fun testMixedText() = runTest {
        val text = "Hello, こんにちは, Привет!"
        assertEquals(8, countTokens(text))
    }

    @Test
    fun testNumbersAndPunctuation() = runTest {
        val text = "Here are some numbers: 123, 456, and 789."
        assertEquals(
            15,
            countTokens(text)
        )
    }

    @Test
    fun testEmptyString() = runTest {
        val text = ""
        assertEquals(0, countTokens(text))
    }
}