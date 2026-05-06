package com.milen.grounpringtonesetter.ui.home

import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.sources.ContactSource
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeEmptyStateMessageTest {

    @Test
    fun `returns groups message when selected source has contacts`() {
        val state = HomeScreenState(
            selectedSource = ContactSource.OnDevice,
            hasContactsInSelectedSource = true
        )

        assertEquals(R.string.on_device_groups_not_found, resolveHomeEmptyStateMessageRes(state))
    }

    @Test
    fun `returns generic message when there are no contacts in selected source`() {
        val state = HomeScreenState(
            selectedSource = ContactSource.OnDevice,
            hasContactsInSelectedSource = false
        )

        assertEquals(R.string.items_not_found, resolveHomeEmptyStateMessageRes(state))
    }

    @Test
    fun `returns generic message when no source is selected yet`() {
        assertEquals(
            R.string.items_not_found,
            resolveHomeEmptyStateMessageRes(HomeScreenState())
        )
    }

    @Test
    fun `shows add group button when selected source has contacts`() {
        val state = HomeScreenState(
            selectedSource = ContactSource.OnDevice,
            hasContactsInSelectedSource = true
        )

        assertEquals(true, shouldShowHomeEmptyAddGroupButton(state))
    }

    @Test
    fun `hides add group button when selected source has no contacts`() {
        val state = HomeScreenState(
            selectedSource = ContactSource.OnDevice,
            hasContactsInSelectedSource = false
        )

        assertEquals(false, shouldShowHomeEmptyAddGroupButton(state))
    }
}
