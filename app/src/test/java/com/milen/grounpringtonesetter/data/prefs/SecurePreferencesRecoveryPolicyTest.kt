package com.milen.grounpringtonesetter.data.prefs

import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SecurePreferencesRecoveryPolicyTest {

    @Test
    fun `migrated preferences do not reopen the legacy encrypted store`() {
        var legacyReadCount = 0

        val result = resolveSecurePreferenceValue(
            encryptedValue = null,
            migrationComplete = true,
            defaultValue = "default",
            decrypt = { it },
            readLegacy = {
                legacyReadCount += 1
                "legacy"
            },
        )

        assertEquals("default", result)
        assertEquals(0, legacyReadCount)
    }

    @Test
    fun `unmigrated preferences can still read a legacy value`() {
        val result = resolveSecurePreferenceValue(
            encryptedValue = null,
            migrationComplete = false,
            defaultValue = "default",
            decrypt = { it },
            readLegacy = { "legacy" },
        )

        assertEquals("legacy", result)
    }

    @Test
    fun `legacy migration failure is reported and treated as an empty store`() {
        val expected = IllegalStateException("corrupt legacy preferences")
        var reported: Exception? = null

        val result = readLegacyPreferencesForMigration(
            readLegacy = { throw expected },
            onFailure = { reported = it },
        )

        assertEquals(emptyMap<String, Any?>(), result)
        assertSame(expected, reported)
    }

    @Test(expected = CancellationException::class)
    fun `legacy migration does not swallow coroutine cancellation`() {
        readLegacyPreferencesForMigration(
            readLegacy = { throw CancellationException("cancelled") },
            onFailure = { error("Cancellation must not be reported as corruption") },
        )
    }
}
