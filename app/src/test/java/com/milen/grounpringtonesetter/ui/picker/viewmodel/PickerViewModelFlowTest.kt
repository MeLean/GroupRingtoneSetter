package com.milen.grounpringtonesetter.ui.picker.viewmodel

import com.milen.grounpringtonesetter.R
import com.milen.grounpringtonesetter.data.repos.GroupReassignmentValidation
import com.milen.grounpringtonesetter.data.repos.RingtoneChoiceOption
import com.milen.grounpringtonesetter.testing.FakeContactsRepository
import com.milen.grounpringtonesetter.testing.RecordingTelemetry
import com.milen.grounpringtonesetter.testing.TestDispatcherProvider
import com.milen.grounpringtonesetter.testing.contact
import com.milen.grounpringtonesetter.testing.label
import com.milen.grounpringtonesetter.ui.picker.PickerEvent
import com.milen.grounpringtonesetter.ui.picker.data.PickerResultData
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
class PickerViewModelFlowTest {

    @Test
    fun `rename mode exposes group and localized title`() = runPickerTest { fixture ->
        val group = label()
        fixture.viewModel.startRename(group)
        advanceUntilIdle()

        assertEquals(R.string.edit_group_name, fixture.viewModel.state.value.titleId)
        assertEquals(
            PickerResultData.GroupNameChange(group),
            fixture.viewModel.state.value.pikerResultData,
        )
        assertFalse(fixture.viewModel.state.value.isLoading)
    }

    @Test
    fun `unchanged or blank rename is rejected before repository call`() = runPickerTest { fixture ->
        val group = label(name = "Friends")
        fixture.viewModel.confirmRename(group, " Friends ")

        assertEquals(
            PickerEvent.ShowErrorById(R.string.enter_group_name),
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
        assertTrue(fixture.contacts.renamedGroups.isEmpty())
    }

    @Test
    fun `valid rename trims input persists and closes`() = runPickerTest { fixture ->
        val group = label(name = "Friends")
        fixture.viewModel.confirmRename(group, " Family ")
        advanceUntilIdle()

        assertEquals(listOf(group to "Family"), fixture.contacts.renamedGroups)
        assertEquals(PickerEvent.Close, withTimeout(1_000) { fixture.viewModel.events.first() })
    }

    @Test
    fun `rename repository failure remains on screen and reports text`() = runPickerTest { fixture ->
        fixture.contacts.renameError = IllegalStateException("rename failed")
        fixture.viewModel.confirmRename(label(), "Family")
        advanceUntilIdle()

        assertEquals(
            PickerEvent.ShowErrorText("rename failed"),
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
        assertEquals(1, fixture.telemetry.errors.size)
        assertFalse(fixture.viewModel.state.value.isLoading)
    }

    @Test
    fun `create group trims name and closes after persistence`() = runPickerTest { fixture ->
        fixture.viewModel.startCreateGroup()
        fixture.viewModel.confirmCreateGroup(" Work ")
        advanceUntilIdle()

        assertEquals(listOf("Work"), fixture.contacts.createdNames)
        assertEquals(PickerEvent.Close, withTimeout(1_000) { fixture.viewModel.events.first() })
    }

    @Test
    fun `managing only removals saves immediately without ringtone choice`() = runPickerTest { fixture ->
        val first = contact(1)
        val second = contact(2)
        val group = label(contacts = listOf(first, second), ringtoneUris = listOf("content://tone/one"))
        fixture.contacts.allContacts.value = listOf(first, second)
        fixture.viewModel.startManageContacts(group)
        advanceUntilIdle()
        fixture.viewModel.updateManageSelection(listOf(first))
        advanceUntilIdle()
        fixture.viewModel.confirmManageContacts(group)
        advanceUntilIdle()

        val call = fixture.contacts.updatedMembers.single()
        assertEquals(listOf(first), call.newSelected)
        assertEquals(null, call.ringtoneUri)
        assertEquals(PickerEvent.Close, withTimeout(1_000) { fixture.viewModel.events.first() })
    }

    @Test
    fun `adding member with one group ringtone applies that ringtone`() = runPickerTest { fixture ->
        val existing = contact(1)
        val added = contact(2)
        val group = label(
            contacts = listOf(existing),
            ringtoneUris = listOf("content://tone/one"),
        )
        fixture.contacts.allContacts.value = listOf(existing, added)
        fixture.contacts.validation = GroupReassignmentValidation(listOf(added), emptyList())
        fixture.viewModel.startManageContacts(group)
        advanceUntilIdle()
        fixture.viewModel.updateManageSelection(listOf(existing, added))
        advanceUntilIdle()
        fixture.viewModel.confirmManageContacts(group)
        advanceUntilIdle()

        assertEquals(listOf(added), fixture.contacts.validatedCandidates.single())
        assertEquals("content://tone/one", fixture.contacts.updatedMembers.single().ringtoneUri)
    }

    @Test
    fun `multiple group ringtones require an explicit choice`() = runPickerTest { fixture ->
        val existing = contact(1)
        val added = contact(2)
        val firstUri = "content://tone/one"
        val secondUri = "content://tone/two"
        val group = label(
            contacts = listOf(existing),
            ringtoneUris = listOf(firstUri, secondUri),
        )
        fixture.contacts.allContacts.value = listOf(existing, added)
        fixture.contacts.validation = GroupReassignmentValidation(listOf(added), emptyList())
        fixture.contacts.ringtoneOptions = listOf(
            RingtoneChoiceOption(firstUri, "One"),
            RingtoneChoiceOption(secondUri, "Two"),
        )
        fixture.viewModel.startManageContacts(group)
        advanceUntilIdle()
        fixture.viewModel.updateManageSelection(listOf(existing, added))
        advanceUntilIdle()
        fixture.viewModel.confirmManageContacts(group)
        advanceUntilIdle()

        val event = withTimeout(1_000) { fixture.viewModel.events.first() }
        assertTrue(event is PickerEvent.AskNewContactsRingtoneChoice)
        assertTrue(fixture.contacts.updatedMembers.isEmpty())

        fixture.viewModel.onRequiredRingtoneChosen(secondUri)
        advanceUntilIdle()
        assertEquals(secondUri, fixture.contacts.updatedMembers.single().ringtoneUri)
    }

    @Test
    fun `continuing blocked reassignment excludes blocked contacts`() = runPickerTest { fixture ->
        val existing = contact(1)
        val allowed = contact(2)
        val blocked = contact(3, name = "Blocked")
        val group = label(contacts = listOf(existing))
        fixture.contacts.allContacts.value = listOf(existing, allowed, blocked)
        fixture.contacts.validation = GroupReassignmentValidation(listOf(allowed), listOf(blocked))
        fixture.viewModel.startManageContacts(group)
        advanceUntilIdle()
        fixture.viewModel.updateManageSelection(listOf(existing, allowed, blocked))
        advanceUntilIdle()
        fixture.viewModel.confirmManageContacts(group)
        advanceUntilIdle()

        assertEquals(
            PickerEvent.AskBlockedContactsContinueOrAbort(listOf("Blocked")),
            withTimeout(1_000) { fixture.viewModel.events.first() },
        )
        fixture.viewModel.onBlockedContactsContinue()
        advanceUntilIdle()

        assertEquals(listOf(existing, allowed), fixture.contacts.updatedMembers.single().newSelected)
    }

    @Test
    fun `aborting blocked reassignment leaves group unchanged`() = runPickerTest { fixture ->
        val existing = contact(1)
        val blocked = contact(2)
        val group = label(contacts = listOf(existing))
        fixture.contacts.allContacts.value = listOf(existing, blocked)
        fixture.contacts.validation = GroupReassignmentValidation(emptyList(), listOf(blocked))
        fixture.viewModel.startManageContacts(group)
        advanceUntilIdle()
        fixture.viewModel.updateManageSelection(listOf(existing, blocked))
        advanceUntilIdle()
        fixture.viewModel.confirmManageContacts(group)
        advanceUntilIdle()
        withTimeout(1_000) { fixture.viewModel.events.first() }

        fixture.viewModel.onBlockedContactsAbort()
        advanceUntilIdle()
        assertTrue(fixture.contacts.updatedMembers.isEmpty())
    }

    @Test
    fun `select all ungrouped keeps existing selection and adds missing contacts once`() =
        runPickerTest { fixture ->
            val existing = contact(1)
            val ungrouped = contact(2)
            val group = label(contacts = listOf(existing))
            fixture.contacts.allContacts.value = listOf(existing, ungrouped)
            fixture.viewModel.startManageContacts(group)
            advanceUntilIdle()

            fixture.viewModel.selectAllUngroupedContacts()
            fixture.viewModel.selectAllUngroupedContacts()
            advanceUntilIdle()

            val state = fixture.viewModel.state.value.pikerResultData as PickerResultData.ManageGroupContacts
            assertEquals(listOf(existing, ungrouped), state.selectedContacts)
        }

    @Test
    fun `rapid confirmation starts only one contact reassignment`() = runPickerTest { fixture ->
        val existing = contact(1)
        val added = contact(2)
        val group = label(contacts = listOf(existing))
        fixture.contacts.allContacts.value = listOf(existing, added)
        fixture.contacts.validation = GroupReassignmentValidation(listOf(added), emptyList())
        fixture.viewModel.startManageContacts(group)
        advanceUntilIdle()
        fixture.viewModel.updateManageSelection(listOf(existing, added))
        advanceUntilIdle()

        fixture.viewModel.confirmManageContacts(group)
        fixture.viewModel.confirmManageContacts(group)
        advanceUntilIdle()

        assertEquals(1, fixture.contacts.validatedCandidates.size)
        assertEquals(1, fixture.contacts.updatedMembers.size)
    }

    private fun runPickerTest(
        block: suspend TestScope.(PickerFixture) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val originalDispatchers = DispatchersProvider.delegate
        Dispatchers.setMain(dispatcher)
        DispatchersProvider.delegate = TestDispatcherProvider(dispatcher)
        try {
            val contacts = FakeContactsRepository()
            val telemetry = RecordingTelemetry()
            val viewModel = PickerViewModel(telemetry, contacts)
            val stateJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.state.collect {}
            }
            try {
                advanceUntilIdle()
                block(PickerFixture(viewModel, contacts, telemetry))
            } finally {
                stateJob.cancel()
                advanceUntilIdle()
            }
        } finally {
            DispatchersProvider.delegate = originalDispatchers
            Dispatchers.resetMain()
        }
    }
}

private data class PickerFixture(
    val viewModel: PickerViewModel,
    val contacts: FakeContactsRepository,
    val telemetry: RecordingTelemetry,
)
