package com.milen.grounpringtonesetter.billing

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdFreeGraceStoreInstrumentedTest {
    private val store by lazy {
        AdFreeGraceStore(ApplicationProvider.getApplicationContext())
    }

    @Before
    fun setUp() {
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun paidGraceCanBeReadAndCleared() {
        val expectedUntilMillis = System.currentTimeMillis() + 60_000L

        store.saveAdFreeUntil(expectedUntilMillis)

        assertEquals(expectedUntilMillis, store.readAdFreeUntil())
        store.clear()
        assertNull(store.readAdFreeUntil())
    }
}
