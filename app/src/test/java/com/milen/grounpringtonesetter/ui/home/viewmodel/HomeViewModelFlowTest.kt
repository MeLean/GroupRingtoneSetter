package com.milen.grounpringtonesetter.ui.home.viewmodel

import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.billing.EntitlementState
import com.milen.grounpringtonesetter.data.accounts.AccountId
import com.milen.grounpringtonesetter.data.exceptions.DeleteLabelException
import com.milen.grounpringtonesetter.data.exceptions.DeleteLabelFailureReason
import com.milen.grounpringtonesetter.data.prefs.HomePreferencesStore
import com.milen.grounpringtonesetter.data.sources.ContactSource
import com.milen.grounpringtonesetter.testing.FakeBillingGateway
import com.milen.grounpringtonesetter.testing.FakeContactSourceRepository
import com.milen.grounpringtonesetter.testing.FakeContactsRepository
import com.milen.grounpringtonesetter.testing.FakeInterstitialAdGateway
import com.milen.grounpringtonesetter.testing.InMemoryHomePreferencesDataSource
import com.milen.grounpringtonesetter.testing.RecordingTelemetry
import com.milen.grounpringtonesetter.testing.TestDispatcherProvider
import com.milen.grounpringtonesetter.testing.label
import com.milen.grounpringtonesetter.ui.home.HomeEvent
import com.milen.grounpringtonesetter.utils.DispatchersProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelFlowTest {

    @Test
    fun `granting permissions refreshes source labels contacts and ringtone summaries`() =
        runHomeTest { fixture ->
            fixture.viewModel.onPermissionsGranted()
            advanceUntilIdle()

            assertEquals(1, fixture.sources.refreshCalls)
            assertEquals(1, fixture.contacts.loadAccountLabelsShallowCalls)
            assertEquals(1, fixture.contacts.refreshContactsCalls)
            assertEquals(1, fixture.contacts.enrichRingtonesCalls)
            assertTrue(fixture.viewModel.state.value.arePermissionsGranted)
            assertFalse(fixture.viewModel.state.value.isLoading)
        }

    @Test
    fun `granting permissions with multiple sources asks user to select one`() = runHomeTest(
        selectedSource = null,
        availableSources = listOf(
            ContactSource.OnDevice,
            ContactSource.CloudAccount(AccountId.of("google", "friend@example.com")),
        ),
    ) { fixture ->
        fixture.viewModel.onPermissionsGranted()

        val event = withTimeout(1_000) { fixture.viewModel.events.first() }
        assertTrue(event is HomeEvent.AskSourceSelection)
        assertEquals(0, fixture.contacts.loadAccountLabelsShallowCalls)
    }

    @Test
    fun `permission refusal stops loading and reports localized error`() = runHomeTest { fixture ->
        fixture.viewModel.onPermissionsRefused()
        advanceUntilIdle()

        assertEquals(
            HomeEvent.ShowErrorById(R.string.need_permission_to_run),
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
        assertFalse(fixture.viewModel.state.value.isLoading)
    }

    @Test
    fun `offline ad supported user navigates to no internet`() = runHomeTest(
        entitlement = EntitlementState.NOT_OWNED,
    ) { fixture ->
        fixture.viewModel.onConnectionChanged(false)
        advanceUntilIdle()

        assertEquals(
            HomeEvent.ConnectionLost,
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
    }

    @Test
    fun `offline owned user remains on home`() = runHomeTest(
        entitlement = EntitlementState.OWNED,
    ) { fixture ->
        fixture.viewModel.onConnectionChanged(false)
        advanceUntilIdle()

        val emitted = fixture.viewModel.events.firstOrNullWithinTestWindow()
        assertEquals(null, emitted)
    }

    @Test
    fun `create request auto selects sole source and navigates`() = runHomeTest(
        selectedSource = null,
    ) { fixture ->
        fixture.viewModel.setUpGroupCreateRequest()
        advanceUntilIdle()

        assertEquals(listOf(ContactSource.OnDevice), fixture.sources.selectedSources)
        assertEquals(
            HomeEvent.NavigateToCreateGroup,
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
        assertEquals(1, fixture.contacts.loadAccountLabelsShallowCalls)
    }

    @Test
    fun `null selected source reports error without loading data`() = runHomeTest { fixture ->
        fixture.viewModel.onAccountsSelected(null)

        assertEquals(
            HomeEvent.ShowErrorById(R.string.something_went_wrong),
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
        assertEquals(1, fixture.telemetry.errors.size)
        assertEquals(0, fixture.contacts.loadAccountLabelsShallowCalls)
    }

    @Test
    fun `reset all ringtones refreshes contacts and reports success`() = runHomeTest { fixture ->
        fixture.viewModel.onResetAllRingtonesConfirmed()
        advanceUntilIdle()

        assertEquals(1, fixture.contacts.clearRingtonesCalls)
        assertEquals(1, fixture.contacts.refreshContactsCalls)
        assertEquals(
            HomeEvent.ShowInfoText(R.string.everything_set),
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
    }

    @Test
    fun `protected group deletion maps to dedicated error`() = runHomeTest { fixture ->
        fixture.contacts.deleteError = DeleteLabelException(
            labelId = 1L,
            reason = DeleteLabelFailureReason.GROUP_PROTECTED,
        )
        fixture.viewModel.onGroupDeleted(label())
        advanceUntilIdle()

        assertEquals(
            HomeEvent.ShowErrorById(R.string.group_cannot_be_deleted),
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
        assertEquals(1, fixture.telemetry.errors.size)
    }

    @Test
    fun `search visibility closing clears current query`() = runHomeTest { fixture ->
        fixture.viewModel.onGroupSearchVisibilityChanged(true)
        fixture.viewModel.onGroupSearchQueryUpdated("friends")
        fixture.viewModel.onGroupSearchVisibilityChanged(false)
        advanceUntilIdle()

        assertFalse(fixture.viewModel.state.value.isGroupSearchVisible)
        assertEquals("", fixture.viewModel.state.value.groupSearchQuery)
    }

    @Test
    fun `feature actions emit their guarded navigation events`() = runHomeTest { fixture ->
        val group = label()
        fixture.viewModel.setUpGroupNameEditing(group)
        assertEquals(
            HomeEvent.NavigateToRename(group),
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )

        fixture.viewModel.setUpContactsManaging(group)
        assertEquals(
            HomeEvent.NavigateToManageContacts(group),
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )

        fixture.viewModel.onDeviceDefaultTonesClicked()
        assertEquals(
            HomeEvent.NavigateToDeviceDefaultTones,
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )

        fixture.viewModel.onBackupRestoreClicked()
        assertEquals(
            HomeEvent.NavigateToBackupRestore,
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
    }

    private fun runHomeTest(
        entitlement: EntitlementState = EntitlementState.OWNED,
        selectedSource: ContactSource? = ContactSource.OnDevice,
        availableSources: List<ContactSource> = listOf(ContactSource.OnDevice),
        block: suspend TestScope.(HomeFixture) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val originalDispatchers = DispatchersProvider.delegate
        Dispatchers.setMain(dispatcher)
        DispatchersProvider.delegate = TestDispatcherProvider(dispatcher)
        try {
            val contacts = FakeContactsRepository()
            val sources = FakeContactSourceRepository(selectedSource, availableSources)
            val telemetry = RecordingTelemetry()
            val viewModel = HomeViewModel(
                adHelper = FakeInterstitialAdGateway(),
                tracker = telemetry,
                billing = FakeBillingGateway(entitlement),
                contactsRepo = contacts,
                sourceRepo = sources,
                homePreferencesStore = HomePreferencesStore(InMemoryHomePreferencesDataSource()),
            )
            val stateJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.state.collect {}
            }
            try {
                advanceUntilIdle()
                block(HomeFixture(viewModel, contacts, sources, telemetry))
            } finally {
                stateJob.cancel()
                advanceUntilIdle()
            }
        } finally {
            DispatchersProvider.delegate = originalDispatchers
            Dispatchers.resetMain()
        }
    }

    private suspend fun kotlinx.coroutines.flow.Flow<HomeEvent>.firstOrNullWithinTestWindow(): HomeEvent? =
        kotlinx.coroutines.withTimeoutOrNull(1) { first() }
}

private data class HomeFixture(
    val viewModel: HomeViewModel,
    val contacts: FakeContactsRepository,
    val sources: FakeContactSourceRepository,
    val telemetry: RecordingTelemetry,
)
