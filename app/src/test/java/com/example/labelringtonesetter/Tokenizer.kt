package com.example.labelringtonesetter

import com.aallam.ktoken.Tokenizer

suspend fun countTokens(text: String): Int {
    val tokenizer = Tokenizer.of(model = "gpt-4o")
    val tokens = tokenizer.encode(text)
    return tokens.size
}
