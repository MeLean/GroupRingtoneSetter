package com.milen.grounpringtonesetter.ui.home.viewmodel

import com.milen.grounpringtonesetter.data.accounts.AccountId
import com.milen.grounpringtonesetter.data.sources.ContactSource
import org.junit.Assert.assertEquals
import org.junit.Test

class CreateGroupSourceResolutionTest {

    @Test
    fun `uses selected source when it is still available`() {
        val selectedSource = ContactSource.OnDevice

        val resolution = resolveCreateGroupSourceResolution(
            selectedSource = selectedSource,
            availableSources = linkedSetOf(selectedSource)
        )

        assertEquals(CreateGroupSourceResolution.UseSelectedSource, resolution)
    }

    @Test
    fun `treats stale selected source as unavailable when current sources are empty`() {
        val staleSource = ContactSource.CloudAccount(AccountId("removed@example.com"))

        val resolution = resolveCreateGroupSourceResolution(
            selectedSource = staleSource,
            availableSources = emptySet()
        )

        assertEquals(CreateGroupSourceResolution.NoSourcesAvailable, resolution)
    }

    @Test
    fun `auto selects the only available source when no valid selection exists`() {
        val availableSource = ContactSource.OnDevice

        val resolution = resolveCreateGroupSourceResolution(
            selectedSource = null,
            availableSources = linkedSetOf(availableSource)
        )

        assertEquals(
            CreateGroupSourceResolution.AutoSelectSingleSource(availableSource),
            resolution
        )
    }

    @Test
    fun `asks user to choose when multiple sources are available and no valid selection exists`() {
        val sources = linkedSetOf(
            ContactSource.OnDevice,
            ContactSource.CloudAccount(AccountId("cloud@example.com"))
        )

        val resolution = resolveCreateGroupSourceResolution(
            selectedSource = ContactSource.CloudAccount(AccountId("missing@example.com")),
            availableSources = sources
        )

        assertEquals(CreateGroupSourceResolution.AskUserToSelectSource(sources), resolution)
    }
}
